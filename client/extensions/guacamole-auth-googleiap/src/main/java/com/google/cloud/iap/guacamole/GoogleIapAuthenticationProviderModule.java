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

import com.google.inject.AbstractModule;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.net.auth.AuthenticationProvider;

/** Guice module which configures HTTP header-specific injections. */
public class GoogleIapAuthenticationProviderModule extends AbstractModule {

  /** Guacamole server environment. */
  private final Environment environment;

  /**
   * A reference to the HTTPHeaderAuthenticationProvider on behalf of which this module has
   * configured injection.
   */
  private final AuthenticationProvider authProvider;

  /**
   * Creates a new HTTP header authentication provider module which configures injection for the
   * HTTPHeaderAuthenticationProvider.
   *
   * @param authProvider The AuthenticationProvider for which injection is being configured.
   * @throws GuacamoleException If an error occurs while retrieving the Guacamole server
   *     environment.
   */
  public GoogleIapAuthenticationProviderModule(AuthenticationProvider authProvider)
      throws GuacamoleException {

    // Get local environment
    this.environment = new LocalEnvironment();

    // Store associated auth provider
    this.authProvider = authProvider;
  }

  @Override
  protected void configure() {

    // Bind core implementations of guacamole-ext classes
    bind(AuthenticationProvider.class).toInstance(authProvider);
    bind(Environment.class).toInstance(environment);

    // Bind IAP-specific classes
    bind(ConfigurationService.class);
  }
}
