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

// import org.apache.guacamole.properties.IntegerGuacamoleProperty;
import org.apache.guacamole.properties.StringGuacamoleProperty;

/**
 * Provides properties required for use of the HTTP header authentication provider. These properties
 * will be read from guacamole.properties when the HTTP authentication provider is used.
 */
public class GoogleIapGuacamoleProperties {

  /** This class should not be instantiated. */
  private GoogleIapGuacamoleProperties() {}

  /** The Google Cloud Project ID hosting the service */
  public static final StringGuacamoleProperty GOOGLE_PROJECT_ID =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-project-id";
        }
      };

  public static final StringGuacamoleProperty GOOGLE_PROJECT_NUMBER =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-project-number";
        }
      };

  /** The k8s Service for the Guacamole Client */
  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_NAME =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-client-service-name";
        }
      };

  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_PORT =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {
          return "google-client-service-port";
        }
      };

  public static final StringGuacamoleProperty GOOGLE_CLIENT_SERVICE_NAMESPACE =
      new StringGuacamoleProperty() {

        @Override
        public String getName() {

          return "google-client-service-namespace";
        }
      };
}
