/**
 * Copyright 2020 Google LLC
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>https://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.iap.guacamole;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.compute.v1.BackendService;
import com.google.cloud.compute.v1.BackendServicesClient;
import com.google.cloud.compute.v1.BackendServicesSettings;
import com.google.cloud.compute.v1.GetBackendServiceRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import java.util.Map;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton service for retrieving Google Cloud project and Load Balancer BackendService
 * configuration required for Identity-Aware Proxy (IAP) JWT verification.
 */
@Singleton
public class ConfigurationService {

  /** Logger for this class. */
  private static final Logger logger = LoggerFactory.getLogger(ConfigurationService.class);

  /** Annotation written by the GKE Ingress controller containing Network Endpoint Group names. */
  static final String ANNOTATION_NEG_STATUS = "cloud.google.com/neg-status";

  /** JSON object key within the NEG status annotation mapping service ports to NEG names. */
  static final String ANNOTATION_NEG_GROUP_KEY = "network_endpoint_groups";

  /** The Guacamole server environment. */
  @Inject private Environment environment;

  /**
   * Lazily initialized and cached numeric BackendService ID. Volatile ensures safe publication
   * across concurrent authentication threads under double-checked locking.
   */
  private volatile String cachedBackendServiceId;

  /** Default constructor for Guice injection. */
  public ConfigurationService() {}

  /**
   * Package-private constructor for unit testing with a mock {@link Environment}.
   *
   * @param environment The Guacamole environment to read properties from.
   */
  ConfigurationService(Environment environment) {
    this.environment = environment;
  }

  /**
   * Returns the Google Cloud Project ID as configured in {@code guacamole.properties}.
   *
   * @return The Google Cloud host project ID.
   * @throws GuacamoleException If the required property is missing or cannot be parsed.
   */
  public String getProjectId() throws GuacamoleException {
    // Require google-project-id so misconfigurations fail fast with a clear error message.
    return environment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_ID);
  }

  /**
   * Returns the numeric Google Cloud Project Number as configured in {@code guacamole.properties}.
   *
   * @return The numeric Google Cloud project number used in IAP JWT audience claims.
   * @throws GuacamoleException If the required property is missing or cannot be parsed.
   */
  public String getProjectNumber() throws GuacamoleException {
    // Require google-project-number because the IAP JWT aud claim uses the numeric project number.
    return environment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_NUMBER);
  }

  /**
   * Returns the numeric Google Cloud Load Balancer BackendService ID used in the IAP JWT audience
   * claim. Checks for an optional explicit property override first, then falls back to lazy,
   * thread-safe dynamic discovery via the Kubernetes and Compute Engine APIs.
   *
   * @return The numeric Google Cloud BackendService ID.
   * @throws GuacamoleException If the BackendService ID cannot be resolved or validated.
   */
  public String getBackendServiceId() throws GuacamoleException {
    // Check if an explicit google-backend-service-id override is configured in guacamole.properties.
    String configuredId =
        environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_BACKEND_SERVICE_ID);
    if (configuredId != null && !configuredId.trim().isEmpty()) {
      return configuredId.trim();
    }

    // Fast path: return the already-resolved BackendService ID without locking.
    String cached = this.cachedBackendServiceId;
    if (cached != null) {
      return cached;
    }

    // Slow path: synchronize so only one thread queries the K8s and GCP APIs on cold start.
    synchronized (this) {
      if (this.cachedBackendServiceId != null) {
        return this.cachedBackendServiceId;
      }

      // Read all required K8s Service coordinates before invoking external APIs.
      String projectId = getProjectId();
      String serviceName =
          environment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAME);
      String namespace =
          environment.getRequiredProperty(
              GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAMESPACE);
      String servicePort =
          environment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_PORT);

      // Resolve the numeric BackendService ID via K8s NEG annotation + Compute Engine API.
      String resolvedId = resolveBackendServiceId(projectId, serviceName, namespace, servicePort);
      if (resolvedId == null || resolvedId.trim().isEmpty() || "0".equals(resolvedId.trim())) {
        throw new GuacamoleServerException(
            "Resolved Google Cloud BackendService ID is empty or invalid: " + resolvedId);
      }

      // Cache only valid non-empty IDs so transient startup errors are never negative-cached.
      this.cachedBackendServiceId = resolvedId.trim();
      logger.info(
          "Cached Google Cloud BackendService ID {} for service {}/{}",
          this.cachedBackendServiceId,
          namespace,
          serviceName);
      return this.cachedBackendServiceId;
    }
  }

  /**
   * Resolves the numeric BackendService ID by querying the Kubernetes Service NEG annotation and
   * looking up the corresponding BackendService in the Google Cloud Compute Engine API. Package-
   * private to allow overriding in unit tests.
   *
   * @param projectId The Google Cloud project ID.
   * @param serviceName The Kubernetes Service name.
   * @param namespace The Kubernetes Service namespace.
   * @param servicePort The Kubernetes Service port key in the NEG status annotation.
   * @return The numeric BackendService ID string.
   * @throws GuacamoleException If either the Kubernetes API or Compute Engine API call fails.
   */
  String resolveBackendServiceId(
      String projectId, String serviceName, String namespace, String servicePort)
      throws GuacamoleException {
    try {
      // Step 1: Query the Kubernetes API for the NEG name attached to the Service port.
      String backendServiceName = getBackendServiceName(serviceName, namespace, servicePort);

      // Step 2: Query the Compute Engine API for the BackendService's numeric ID.
      return getServiceId(projectId, backendServiceName);
    } catch (ApiException e) {
      // Log at ERROR level with the K8s API response body so RBAC or missing-service issues are visible.
      logger.error(
          "Kubernetes API error fetching NEG status for service {}/{}: {} (body: {})",
          namespace,
          serviceName,
          e.getMessage(),
          e.getResponseBody(),
          e);
      // Propagate as GuacamoleServerException so the caller does not negative-cache or mask the error.
      throw new GuacamoleServerException(
          "Kubernetes API error fetching BackendService name for " + namespace + "/" + serviceName,
          e);
    } catch (IOException | RuntimeException e) {
      // Log at ERROR level so IAM permission or network errors are immediately actionable in Pod logs.
      logger.error(
          "Error resolving Google Cloud BackendService ID for service {}/{}: {}",
          namespace,
          serviceName,
          e.getMessage(),
          e);
      throw new GuacamoleServerException(
          "Failed to resolve Google Cloud BackendService ID for " + namespace + "/" + serviceName,
          e);
    }
  }

  /**
   * Queries the Google Cloud Compute Engine API to retrieve the numeric ID for a named
   * BackendService.
   *
   * @param projectId The Google Cloud project ID.
   * @param backendService The BackendService resource name (matching the GKE NEG name).
   * @return The numeric BackendService ID as a String.
   * @throws IOException If Application Default Credentials or the Compute Engine client cannot be
   *     initialized.
   * @throws GuacamoleException If the BackendService response does not contain a valid ID.
   */
  private static String getServiceId(String projectId, String backendService)
      throws IOException, GuacamoleException {
    // Obtain Application Default Credentials from the GKE Workload Identity metadata server.
    Credentials credentials = GoogleCredentials.getApplicationDefault();

    // Build Compute Engine client settings using the Workload Identity credentials.
    BackendServicesSettings backendServiceSettings =
        BackendServicesSettings.newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
            .build();

    // Open the BackendServicesClient inside try-with-resources so gRPC/HTTP channels close cleanly.
    try (BackendServicesClient backendServiceClient =
        BackendServicesClient.create(backendServiceSettings)) {

      // Request metadata for the named global BackendService in the target project.
      GetBackendServiceRequest request =
          GetBackendServiceRequest.newBuilder()
              .setBackendService(backendService)
              .setProject(projectId)
              .build();

      // Verify that the returned BackendService resource carries a non-zero numeric ID.
      BackendService response = backendServiceClient.get(request);
      if (!response.hasId() || response.getId() == 0L) {
        throw new GuacamoleServerException(
            "Compute Engine BackendService '" + backendService + "' did not return a valid ID.");
      }

      // Convert the 64-bit unsigned numeric ID to its decimal string representation.
      String backendServiceId = Long.toUnsignedString(response.getId());
      logger.debug("Resolved BackendService ID {} from Google Cloud API", backendServiceId);
      return backendServiceId;
    }
  }

  /**
   * Queries the in-cluster Kubernetes API to read the {@code cloud.google.com/neg-status}
   * annotation from the specified Service and extract the BackendService/NEG name.
   *
   * @param serviceName The Kubernetes Service name.
   * @param namespace The Kubernetes namespace containing the Service.
   * @param servicePort The Service port mapped to the NEG.
   * @return The BackendService/NEG name string.
   * @throws IOException If the in-cluster Kubernetes configuration cannot be loaded.
   * @throws ApiException If the Kubernetes API request fails.
   * @throws GuacamoleException If the Service annotation is missing or malformed.
   */
  private static String getBackendServiceName(
      String serviceName, String namespace, String servicePort)
      throws IOException, ApiException, GuacamoleException {
    // Build a local in-cluster ApiClient and pass it directly to CoreV1Api without mutating global state.
    ApiClient client = ClientBuilder.cluster().build();
    CoreV1Api api = new CoreV1Api(client);

    // Fetch the Service object from the Kubernetes API using the client-java 21.x fluent builder.
    V1Service service = api.readNamespacedService(serviceName, namespace).execute();
    V1ObjectMeta metadata = (service != null) ? service.getMetadata() : null;
    Map<String, String> annotations = (metadata != null) ? metadata.getAnnotations() : null;

    // Extract the cloud.google.com/neg-status JSON string from the Service annotations.
    String negStatus = (annotations != null) ? annotations.get(ANNOTATION_NEG_STATUS) : null;
    String negName = parseNegNameFromAnnotation(negStatus, servicePort);
    logger.debug("Resolved BackendService name {} from Kubernetes API", negName);
    return negName;
  }

  /**
   * Parses the {@code cloud.google.com/neg-status} JSON annotation and extracts the NEG name for
   * the specified service port. Package-private for direct unit testing.
   *
   * @param negStatusJson The raw JSON string from the {@code cloud.google.com/neg-status}
   *     annotation.
   * @param servicePort The port key (e.g., {@code "80"}) inside {@code network_endpoint_groups}.
   * @return The non-empty NEG / BackendService name.
   * @throws GuacamoleException If the annotation is missing, malformed, or lacks the port mapping.
   */
  static String parseNegNameFromAnnotation(String negStatusJson, String servicePort)
      throws GuacamoleException {
    // Reject missing or blank annotations (e.g., if the GKE Ingress controller is still provisioning).
    if (negStatusJson == null || negStatusJson.trim().isEmpty()) {
      throw new GuacamoleServerException(
          "Missing or empty '" + ANNOTATION_NEG_STATUS + "' annotation on Kubernetes Service.");
    }

    try {
      // Parse the top-level JSON object and locate the network_endpoint_groups map.
      JsonElement rootElement = JsonParser.parseString(negStatusJson);
      if (!rootElement.isJsonObject()) {
        throw new GuacamoleServerException(
            "Annotation '" + ANNOTATION_NEG_STATUS + "' is not a valid JSON object.");
      }

      // Extract the network_endpoint_groups sub-object from the annotation JSON.
      JsonObject negStatusObj = rootElement.getAsJsonObject();
      JsonObject negGroups = negStatusObj.getAsJsonObject(ANNOTATION_NEG_GROUP_KEY);
      if (negGroups == null) {
        throw new GuacamoleServerException(
            "Annotation '"
                + ANNOTATION_NEG_STATUS
                + "' does not contain '"
                + ANNOTATION_NEG_GROUP_KEY
                + "'.");
      }

      // Look up the NEG name corresponding to the configured Kubernetes Service port.
      JsonElement negElement = negGroups.get(servicePort);
      if (negElement == null
          || negElement.isJsonNull()
          || negElement.getAsString().trim().isEmpty()) {
        // Fail fast with a clear message if the port key is missing or mapped to a blank NEG name.
        throw new GuacamoleServerException(
            "No NEG found for service port '"
                + servicePort
                + "' in '"
                + ANNOTATION_NEG_STATUS
                + "'.");
      }

      // Trim surrounding whitespace before querying the Compute Engine BackendServices API.
      return negElement.getAsString().trim();
    } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
      // Wrap any Gson parsing errors in a descriptive GuacamoleServerException.
      throw new GuacamoleServerException(
          "Malformed JSON in '" + ANNOTATION_NEG_STATUS + "' annotation.", e);
    }
  }
}
