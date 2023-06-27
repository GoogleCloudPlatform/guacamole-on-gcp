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
import com.google.inject.Inject;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service for retrieving configuration information for IAP header-based authentication. */
public class ConfigurationService {

  /** The Guacamole server environment. */
  @Inject private Environment environment;

  private static final Logger logger = LoggerFactory.getLogger(ConfigurationService.class);

  private static String ANNOTATION_NEG_STATUS = "cloud.google.com/neg-status";
  private static String ANNOTATION_NEG_GROUP_KEY = "network_endpoint_groups";

  /**
   * Returns the Google Cloud Project ID as configured with guacamole.properties used for Google IAP
   * authentication.
   *
   * @return The Google Cloud host project id, as configured with guacamole.properties.
   * @throws GuacamoleException If guacamole.properties cannot be parsed.
   */
  public String getProjectId() throws GuacamoleException {
    return environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_ID);
  }

  /**
   * Returns the Google Cloud Project ID as configured with guacamole.properties used for Google IAP
   * authentication.
   *
   * @return The Google Cloud host project id, as configured with guacamole.properties.
   * @throws GuacamoleException If guacamole.properties cannot be parsed.
   */
  public String getProjectNumber() throws GuacamoleException {
    return environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_NUMBER);
  }

  /**
   * Returns the Loadbalancer BackendService ID as configured with guacamole.properties used for
   * Google IAP authentication.
   *
   * @return The Google Loadbalancer BackendService ID, as configured with guacamole.properties.
   * @throws GuacamoleException If guacamole.properties cannot be parsed.
   */
  public String getBackendServiceId() throws GuacamoleException {

    String serviceId;
    try {
      String backendServiceName =
          getBackendServiceName(
              environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAME),
              environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAMESPACE),
              environment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_PORT));

      serviceId = getServiceId(getProjectId(), backendServiceName);
    } catch (IOException e) {
      logger.debug("Exception accessing GCP API: {}", e.toString());
      throw new GuacamoleException("IOException fetching Google Backend Service ID");
    } catch (ApiException e) {
      logger.debug("Exception accessing GCP API: {} {}", e.toString(), e.getResponseBody());
      throw new GuacamoleException("ApiException fetching Google Backend Service ID");
    }

    return serviceId;
  }

  /**
   * Retrieves the Backend Service ID for a named Backend Service, in a Google Cloud Project
   *
   * @param projectId - Project Name
   * @param backendService - Backend Service Name
   * @return backendServiceId - Backend Service ID
   * @throws IOException
   */
  private static String getServiceId(String projectId, String backendService) throws IOException {

    String backendServiceId = "";
    Credentials myCredentials = GoogleCredentials.getApplicationDefault();

    BackendServicesSettings backendServiceSettings =
        BackendServicesSettings.newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(myCredentials))
            .build();

    try (BackendServicesClient backendServiceClient =
        BackendServicesClient.create(backendServiceSettings)) {

      GetBackendServiceRequest request =
          GetBackendServiceRequest.newBuilder()
              .setBackendService(backendService)
              .setProject(projectId)
              .build();

      BackendService response = backendServiceClient.get(request);
      backendServiceId = Long.toString(response.getId());
    } catch (Exception e) {
      logger.debug("Exception accessing GCP API: {}", e.toString());
      e.printStackTrace();
    }
    logger.debug("Got BackendServiceId {} from Google Cloud API", backendServiceId);
    return backendServiceId;
  }

  /**
   * Use the k8s api to retrieve annotations from the service object - which includes a reference to
   * the Network Endpoints Groups being used by the load balancer
   *
   * @param serviceName - K8s Service object
   * @param namespace - K8s namespace containing the Service
   * @param servicePort - which port the Service is listening on
   * @return - String containing the BackendService name
   * @throws IOException
   * @throws ApiException
   */
  private static String getBackendServiceName(
      String serviceName, String namespace, String servicePort) throws IOException, ApiException {
    /* Connect to the k8s api */
    ApiClient client = ClientBuilder.cluster().build();
    Configuration.setDefaultApiClient(client);
    CoreV1Api api = new CoreV1Api();

    /* Retrieve the named service and extract its annotations */
    V1Service service = api.readNamespacedService(serviceName, namespace, "false");
    String negStatus = service.getMetadata().getAnnotations().get(ANNOTATION_NEG_STATUS);

    /* Parse the network_endpoint_groups annotation */
    JsonObject negStatusObj = JsonParser.parseString(negStatus).getAsJsonObject();
    JsonElement negs = negStatusObj.getAsJsonObject(ANNOTATION_NEG_GROUP_KEY).get(servicePort);

    logger.debug("Got BackendServiceName {} from k8s API", negs.getAsString());
    return negs.getAsString();
  }
}
