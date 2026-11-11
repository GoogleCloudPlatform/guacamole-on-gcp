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
import java.util.Locale;
import java.util.Map;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.properties.StringGuacamoleProperty;
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

  /** Default Kubernetes Service name for the Guacamole Client when not explicitly overridden. */
  static final String DEFAULT_CLIENT_SERVICE_NAME = "guacamole-client-service";

  /** Default Kubernetes Namespace for the Guacamole Client Service when not explicitly overridden. */
  static final String DEFAULT_CLIENT_SERVICE_NAMESPACE = "guacamole";

  /** Default Kubernetes Service port mapped to the GKE Ingress NEG when not explicitly overridden. */
  static final String DEFAULT_CLIENT_SERVICE_PORT = "80";

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
   * Returns the Google Cloud Project ID from {@code guacamole.properties} or the {@code
   * GOOGLE_PROJECT_ID} environment variable.
   *
   * @return The Google Cloud host project ID.
   * @throws GuacamoleException If the required property is missing or cannot be parsed.
   */
  public String getProjectId() throws GuacamoleException {
    // Require google-project-id (or GOOGLE_PROJECT_ID) so misconfigurations fail fast.
    return getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_ID);
  }

  /**
   * Returns the numeric Google Cloud Project Number from {@code guacamole.properties} or the {@code
   * GOOGLE_PROJECT_NUMBER} environment variable.
   *
   * @return The numeric Google Cloud project number used in IAP JWT audience claims.
   * @throws GuacamoleException If the required property is missing or cannot be parsed.
   */
  public String getProjectNumber() throws GuacamoleException {
    // Require google-project-number (or GOOGLE_PROJECT_NUMBER) for the IAP JWT aud claim.
    return getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_NUMBER);
  }

  /**
   * Returns the numeric Google Cloud Load Balancer BackendService ID used in the IAP JWT audience
   * claim. Checks for an optional explicit property/env override first, then falls back to lazy,
   * thread-safe dynamic discovery via the Kubernetes and Compute Engine APIs.
   *
   * @return The numeric Google Cloud BackendService ID.
   * @throws GuacamoleException If the BackendService ID cannot be resolved or validated.
   */
  public String getBackendServiceId() throws GuacamoleException {
    // Check if an explicit google-backend-service-id (or GOOGLE_BACKEND_SERVICE_ID) override is set.
    String configuredId =
        getOptionalProperty(GoogleIapGuacamoleProperties.GOOGLE_BACKEND_SERVICE_ID, null);
    if (configuredId != null) {
      return configuredId;
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

      // Read Kubernetes Service coordinates (falling back to standard GKE manifest defaults).
      String projectId = getProjectId();
      String serviceName =
          getOptionalProperty(
              GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAME,
              DEFAULT_CLIENT_SERVICE_NAME);
      String namespace =
          getOptionalProperty(
              GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAMESPACE,
              DEFAULT_CLIENT_SERVICE_NAMESPACE);
      String servicePort =
          getOptionalProperty(
              GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_PORT,
              DEFAULT_CLIENT_SERVICE_PORT);

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
   * Resolves a configuration property from the Guacamole {@link Environment}, falling back to the
   * corresponding uppercase underscore OS environment variable (e.g., {@code GOOGLE_PROJECT_ID})
   * and finally to {@code defaultValue} if neither is set.
   *
   * @param property The Guacamole property definition to look up.
   * @param defaultValue The fallback value to return when neither property nor env var is set.
   * @return The trimmed configuration value, or {@code defaultValue} if unset.
   * @throws GuacamoleException If the Guacamole environment fails to parse the property.
   */
  private String getOptionalProperty(StringGuacamoleProperty property, String defaultValue)
      throws GuacamoleException {
    // Check guacamole.properties (and LocalEnvironment if enable-environment-properties is set).
    String value = environment.getProperty(property);
    if (value != null && !value.trim().isEmpty()) {
      return value.trim();
    }

    // Fall back to direct OS environment variable lookup (e.g., GOOGLE_PROJECT_ID).
    String envName = property.getName().replace('-', '_').toUpperCase(Locale.ROOT);
    String envValue = getEnvironmentVariable(envName);
    if (envValue != null && !envValue.trim().isEmpty()) {
      return envValue.trim();
    }

    return defaultValue;
  }

  /**
   * Resolves a required configuration property from the Guacamole {@link Environment} or its
   * corresponding OS environment variable, throwing a {@link GuacamoleException} if neither is set.
   *
   * @param property The required Guacamole property definition.
   * @return The non-empty trimmed configuration value.
   * @throws GuacamoleException If the property is missing from both Guacamole and OS environments.
   */
  private String getRequiredProperty(StringGuacamoleProperty property) throws GuacamoleException {
    // First check getProperty() and OS environment variable fallback.
    String value = getOptionalProperty(property, null);
    if (value != null) {
      return value;
    }

    // Delegate to environment.getRequiredProperty() so LocalEnvironment or test mocks can supply/throw.
    String requiredValue = environment.getRequiredProperty(property);
    if (requiredValue != null && !requiredValue.trim().isEmpty()) {
      return requiredValue.trim();
    }

    String envName = property.getName().replace('-', '_').toUpperCase(Locale.ROOT);
    throw new GuacamoleServerException(
        "Property '"
            + property.getName()
            + "' (or environment variable '"
            + envName
            + "') is required.");
  }

  /**
   * Reads an OS environment variable by name. Package-private to allow deterministic overriding in
   * unit tests.
   *
   * @param name The environment variable name (e.g., {@code GOOGLE_PROJECT_ID}).
   * @return The environment variable value, or {@code null} if not defined.
   */
  String getEnvironmentVariable(String name) {
    return System.getenv(name);
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
