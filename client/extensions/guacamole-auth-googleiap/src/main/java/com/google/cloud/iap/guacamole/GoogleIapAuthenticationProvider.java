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

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AbstractAuthenticationProvider;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;

/**
 * Guacamole authentication backend which authenticates users using a cryptographically signed
 * Google Cloud Identity-Aware Proxy (IAP) JWT passed in the {@code x-goog-iap-jwt-assertion} HTTP
 * header. No storage for connections is provided—only authentication. Connection storage is
 * provided by a companion extension such as {@code guacamole-auth-jdbc-mysql}.
 */
public class GoogleIapAuthenticationProvider extends AbstractAuthenticationProvider {

  /** Guice injector which manages the singleton object graph of this authentication provider. */
  private final Injector injector;

  /**
   * Creates a new {@link GoogleIapAuthenticationProvider} that authenticates users via Google IAP
   * JWT headers.
   *
   * @throws GuacamoleException If a required property is missing, or an error occurs while
   *     initializing the Guacamole environment.
   */
  public GoogleIapAuthenticationProvider() throws GuacamoleException {

    // Set up the Guice injector with singleton bindings for ConfigurationService and AuthenticationProviderService.
    injector = Guice.createInjector(new GoogleIapAuthenticationProviderModule(this));
  }

  @Override
  public String getIdentifier() {
    // Unique identifier for the Google IAP authentication provider.
    return "googleiap";
  }

  @Override
  public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {

    // Delegate credential verification to the singleton AuthenticationProviderService instance.
    AuthenticationProviderService authProviderService =
        injector.getInstance(AuthenticationProviderService.class);
    return authProviderService.authenticateUser(credentials);
  }
}
