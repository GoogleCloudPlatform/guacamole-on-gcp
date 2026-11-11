/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.google.cloud.iap.guacamole.user;

import com.google.inject.Inject;
import java.util.Locale;
import org.apache.guacamole.net.auth.AbstractAuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Credentials;

/**
 * A Google Cloud Identity-Aware Proxy (IAP) implementation of {@link
 * org.apache.guacamole.net.auth.AuthenticatedUser}, associating a verified email username and
 * particular set of credentials with the Google IAP authentication provider.
 */
public class AuthenticatedUser extends AbstractAuthenticatedUser {

  /** Reference to the authentication provider associated with this authenticated user. */
  @Inject private AuthenticationProvider authProvider;

  /** The credentials provided when this user was authenticated. */
  private Credentials credentials;

  /**
   * Initializes this AuthenticatedUser using the given verified email username and credentials.
   *
   * @param username The email address of the user that was authenticated by Google IAP.
   * @param credentials The credentials provided when this user was authenticated.
   */
  public void init(String username, Credentials credentials) {
    // Store the credentials and normalize the user identifier to lowercase using Locale.ROOT.
    this.credentials = credentials;
    setIdentifier(username.toLowerCase(Locale.ROOT));
  }

  @Override
  public AuthenticationProvider getAuthenticationProvider() {
    // Return the GoogleIapAuthenticationProvider that authenticated this user.
    return authProvider;
  }

  @Override
  public Credentials getCredentials() {
    // Return the credentials associated with this user's session.
    return credentials;
  }
}
