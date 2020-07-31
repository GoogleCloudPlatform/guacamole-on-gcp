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

import com.google.cloud.iap.guacamole.user.AuthenticatedUser;
// import java.security.Principal;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URL;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.CredentialsInfo;
import org.apache.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service providing convenience functions for the IAP Header AuthenticationProvider implementation.
 */
public class AuthenticationProviderService {
  /** Logger for this class. */
  private static final Logger logger = LoggerFactory.getLogger(AuthenticationProviderService.class);

  private static final String PUBLIC_KEY_VERIFICATION_URL =
      "https://www.gstatic.com/iap/verify/public_key-jwk";

  private static final String IAP_ISSUER_URL = "https://cloud.google.com/iap";

  // using a simple cache with no eviction for this sample
  private final Map<String, JWK> keyCache = new HashMap<>();

  private static Clock clock = Clock.systemUTC();

  /** Service for retrieving header configuration information. */
  @Inject private ConfigurationService confService;

  /** Provider for AuthenticatedUser objects. */
  @Inject private Provider<AuthenticatedUser> authenticatedUserProvider;

  /**
   * Returns an AuthenticatedUser representing the user authenticated by the given credentials.
   *
   * @param credentials The credentials to use for authentication.
   * @return An AuthenticatedUser representing the user authenticated by the given credentials.
   * @throws GuacamoleException If an error occurs while authenticating the user, or if access is
   *     denied.
   */
  public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {

    // Pull HTTP header from request if present
    HttpServletRequest request = credentials.getRequest();
    if (request == null) {
      throw new GuacamoleInvalidCredentialsException(
          "Invalid login (No HTTPRequest Passed to IAP Auth).", CredentialsInfo.USERNAME_PASSWORD);
    } else {

      // Check for iap jwt header in incoming request
      String jwtToken = request.getHeader("x-goog-iap-jwt-assertion");

      if (jwtToken == null) {
        // Authentication not provided via header, yet, so we request it.
        throw new GuacamoleInvalidCredentialsException(
            "Invalid login (No JWT Token).", CredentialsInfo.USERNAME_PASSWORD);
      } else {
        logger.debug("Google-IAP: Retrieved JWT from header");
      }

      if (!verifyJwt(
          jwtToken,
          String.format(
              "/projects/%s/global/backendServices/%s",
              confService.getProjectNumber(), confService.getBackendServiceId()))) {
        // JWT is not a valid token
        throw new GuacamoleInvalidCredentialsException(
            "Invalid login (JWT Failed Validation).", CredentialsInfo.USERNAME_PASSWORD);
      }

      SignedJWT signedJwt = SignedJWT.parse(jwtToken);
      JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
      String username = claims.getClaim("email").toString();

      if (username != null) {
        AuthenticatedUser authenticatedUser = authenticatedUserProvider.get();
        authenticatedUser.init(username, credentials);
        return authenticatedUser;
      } else {
        throw new GuacamoleInvalidCredentialsException(
            "Invalid login (empty email from JWT claims)", CredentialsInfo.USERNAME_PASSWORD);
      }
    }
  }

  private ECPublicKey getKey(String kid, String alg) throws Exception {
    JWK jwk = keyCache.get(kid);
    if (jwk == null) {
      // update cache loading jwk public key data from url
      JWKSet jwkSet = JWKSet.load(new URL(PUBLIC_KEY_VERIFICATION_URL));
      for (JWK key : jwkSet.getKeys()) {
        keyCache.put(key.getKeyID(), key);
      }
      jwk = keyCache.get(kid);
    }
    // confirm that algorithm matches
    if (jwk != null && jwk.getAlgorithm().getName().equals(alg)) {
      return ECKey.parse(jwk.toJSONString()).toECPublicKey();
    }
    return null;
  }

  private boolean verifyJwt(String jwtToken, String expectedAudience) throws Exception {

    logger.debug("Google-IAP: Validating JWT with aud {}", expectedAudience);

    // parse signed token into header / claims
    SignedJWT signedJwt = SignedJWT.parse(jwtToken);
    JWSHeader jwsHeader = signedJwt.getHeader();

    // header must have algorithm("alg") and "kid"
    Preconditions.checkNotNull(jwsHeader.getAlgorithm());
    Preconditions.checkNotNull(jwsHeader.getKeyID());

    JWTClaimsSet claims = signedJwt.getJWTClaimsSet();

    // claims must have audience, issuer
    Preconditions.checkArgument(claims.getAudience().contains(expectedAudience));
    Preconditions.checkArgument(claims.getIssuer().equals(IAP_ISSUER_URL));

    // claim must have issued at time in the past
    Date currentTime = Date.from(Instant.now(clock));
    Preconditions.checkArgument(claims.getIssueTime().before(currentTime));
    // claim must have expiration time in the future
    Preconditions.checkArgument(claims.getExpirationTime().after(currentTime));

    // must have subject, email
    Preconditions.checkNotNull(claims.getSubject());
    Preconditions.checkNotNull(claims.getClaim("email"));

    // verify using public key : lookup with key id, algorithm name provided
    ECPublicKey publicKey = getKey(jwsHeader.getKeyID(), jwsHeader.getAlgorithm().getName());

    Preconditions.checkNotNull(publicKey);
    JWSVerifier jwsVerifier = new ECDSAVerifier(publicKey);
    return signedJwt.verify(jwsVerifier);
  }
}
