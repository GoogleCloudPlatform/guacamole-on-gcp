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

import org.apache.guacamole.properties.StringGuacamoleProperty;

/**
 * Provides properties required for use of the Google Cloud Identity-Aware Proxy (IAP)
 * authentication provider. These properties are read from {@code guacamole.properties} (or
 * corresponding environment variables when {@code enable-environment-properties} is active).
 */
public class GoogleIapGuacamoleProperties {

  /** This utility class should not be instantiated. */
  private GoogleIapGuacamoleProperties() {}

  /** The Google Cloud Project ID hosting the GKE cluster and Load Balancer. */
  public static final StringGuacamoleProperty GOOGLE_PROJECT_ID =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-project-id";
        }
      };

  /** The numeric Google Cloud Project Number used to construct the expected IAP JWT audience. */
  public static final StringGuacamoleProperty GOOGLE_PROJECT_NUMBER =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-project-number";
        }
      };

  /** The Kubernetes Service name for the Guacamole Client (e.g., guacamole-client-service). */
  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_NAME =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-client-service-name";
        }
      };

  /** The Kubernetes Service port exposed to the Ingress NEG (e.g., 80). */
  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_PORT =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-client-service-port";
        }
      };

  /** The Kubernetes Namespace containing the Guacamole Client Service (e.g., guacamole). */
  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_NAMESPACE =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-client-service-namespace";
        }
      };

  /**
   * Optional explicit override for the numeric Google Cloud BackendService ID. When set, dynamic
   * Kubernetes/Compute Engine API discovery is bypassed.
   */
  public static final StringGuacamoleProperty GOOGLE_BACKEND_SERVICE_ID =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-backend-service-id";
        }
      };
}
