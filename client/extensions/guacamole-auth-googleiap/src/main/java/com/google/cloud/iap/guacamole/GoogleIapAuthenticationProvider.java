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
 * Guacamole authentication backend which authenticates users using an arbitrary external HTTP
 * header. No storage for connections is provided - only authentication. Storage must be provided by
 * some other extension.
 */
public class GoogleIapAuthenticationProvider extends AbstractAuthenticationProvider {

  /** Injector which will manage the object graph of this authentication provider. */
  private final Injector injector;

  /**
   * Creates a new HTTPHeaderAuthenticationProvider that authenticates users using HTTP headers.
   *
   * @throws GuacamoleException If a required property is missing, or an error occurs while parsing
   *     a property.
   */
  public GoogleIapAuthenticationProvider() throws GuacamoleException {

    // Set up Guice injector.
    injector = Guice.createInjector(new GoogleIapAuthenticationProviderModule(this));
  }

  @Override
  public String getIdentifier() {
    return "googleiap";
  }

  @Override
  public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {

    // Pass credentials to authentication service.
    AuthenticationProviderService authProviderService =
        injector.getInstance(AuthenticationProviderService.class);
    return authProviderService.authenticateUser(credentials);
  }
}
