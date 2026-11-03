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
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.CredentialsInfo;
import org.apache.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton service that authenticates Guacamole users by validating the Google Cloud
 * Identity-Aware Proxy (IAP) {@code x-goog-iap-jwt-assertion} HTTP header.
 */
@Singleton
public class AuthenticationProviderService {

  /** Logger for this class. */
  private static final Logger logger = LoggerFactory.getLogger(AuthenticationProviderService.class);

  /** HTTP request header attached by Google Cloud Identity-Aware Proxy containing the signed JWT. */
  static final String IAP_JWT_HEADER = "x-goog-iap-jwt-assertion";

  /** Google hosted JWK Set URL for verifying ES256 IAP JWT signatures. */
  private static final String PUBLIC_KEY_VERIFICATION_URL =
      "https://www.gstatic.com/iap/verify/public_key-jwk";

  /** Expected issuer claim in all Google Cloud IAP JWTs. */
  private static final String IAP_ISSUER_URL = "https://cloud.google.com/iap";

  /** Allowed clock-skew tolerance (30 seconds) when validating JWT iat and exp timestamps. */
  private static final long CLOCK_SKEW_SECONDS = 30L;

  /** Thread-safe cache of Google IAP public keys indexed by JWK key ID (kid). */
  private final Map<String, JWK> keyCache = new ConcurrentHashMap<>();

  /** Clock instance used to evaluate token issue and expiration timestamps; injectable for tests. */
  private Clock clock = Clock.systemUTC();

  /** Service for retrieving Google Cloud project number and BackendService ID. */
  @Inject private ConfigurationService confService;

  /** Guice provider for creating AuthenticatedUser instances upon successful verification. */
  @Inject private Provider<AuthenticatedUser> authenticatedUserProvider;

  /** Default constructor for Guice injection. */
  public AuthenticationProviderService() {}

  /**
   * Package-private constructor for unit testing with mock collaborators.
   *
   * @param confService The configuration service providing project number and BackendService ID.
   * @param authenticatedUserProvider The provider for creating AuthenticatedUser instances.
   */
  AuthenticationProviderService(
      ConfigurationService confService, Provider<AuthenticatedUser> authenticatedUserProvider) {
    this.confService = confService;
    this.authenticatedUserProvider = authenticatedUserProvider;
  }

  /**
   * Overrides the clock used for JWT timestamp verification during unit tests.
   *
   * @param clock The clock to use for current time comparisons.
   */
  void setClock(Clock clock) {
    this.clock = Preconditions.checkNotNull(clock);
  }

  /**
   * Returns an {@link AuthenticatedUser} representing the user authenticated by the given
   * credentials and IAP JWT header.
   *
   * @param credentials The credentials containing the incoming {@link HttpServletRequest}.
   * @return An {@link AuthenticatedUser} initialized with the verified lowercase email address.
   * @throws GuacamoleException If the request lacks a valid IAP JWT or if backend configuration
   *     resolution fails.
   */
  public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {
    // Ensure an HTTP request is associated with the incoming credentials.
    HttpServletRequest request = credentials.getRequest();
    if (request == null) {
      throw new GuacamoleInvalidCredentialsException(
          "Invalid login (No HTTPRequest Passed to IAP Auth).", CredentialsInfo.USERNAME_PASSWORD);
    }

    // Extract the signed IAP JWT assertion header from the incoming HTTP request.
    String jwtToken = request.getHeader(IAP_JWT_HEADER);
    if (jwtToken == null || jwtToken.trim().isEmpty()) {
      throw new GuacamoleInvalidCredentialsException(
          "Invalid login (No JWT Token).", CredentialsInfo.USERNAME_PASSWORD);
    }
    logger.debug("Google-IAP: Retrieved JWT from {} header", IAP_JWT_HEADER);

    // Resolve the expected audience outside the JWT try/catch so infrastructure or RBAC/IAM errors
    // propagate as GuacamoleServerException (logged at ERROR) instead of being masked as bad credentials.
    String expectedAudience =
        String.format(
            "/projects/%s/global/backendServices/%s",
            confService.getProjectNumber(), confService.getBackendServiceId());

    // Parse, verify the cryptographic signature and claims, and extract the email in a single pass.
    String verifiedEmail;
    try {
      verifiedEmail = verifyAndExtractEmail(jwtToken, expectedAudience);
    } catch (Exception e) {
      logger.warn("Google-IAP: JWT validation failed: {}", e.getMessage());
      throw new GuacamoleInvalidCredentialsException(
          "Invalid login (JWT Failed Validation).", e, CredentialsInfo.USERNAME_PASSWORD);
    }

    // Normalize the verified email to lowercase using Locale.ROOT and populate credentials username
    // so Guacamole 1.6.0 ${GUAC_USERNAME} parameter tokens resolve as expected.
    String normalizedEmail = verifiedEmail.toLowerCase(Locale.ROOT);
    credentials.setUsername(normalizedEmail);

    // Initialize and return the Guacamole AuthenticatedUser for this session.
    AuthenticatedUser authenticatedUser = authenticatedUserProvider.get();
    authenticatedUser.init(normalizedEmail, credentials);
    return authenticatedUser;
  }

  /**
   * Loads the current Google IAP public JWK set from Google's verification endpoint. Package-
   * private so unit tests can supply a local test JWKSet without network calls.
   *
   * @return The loaded {@link JWKSet}.
   * @throws Exception If the JWK set cannot be downloaded or parsed.
   */
  JWKSet loadJwkSet() throws Exception {
    // Use URI.create(...).toURL() instead of the deprecated URL(String) constructor in Java 21.
    return JWKSet.load(URI.create(PUBLIC_KEY_VERIFICATION_URL).toURL());
  }

  /**
   * Retrieves the EC public key matching the given key ID ({@code kid}) and algorithm ({@code
   * alg}), refreshing the cached JWK set and evicting rotated keys on a cache miss.
   *
   * @param kid The key ID from the JWT header.
   * @param alg The JWS algorithm name from the JWT header (expected {@code ES256}).
   * @return The matching {@link ECPublicKey}, or {@code null} if no matching EC key exists.
   * @throws Exception If refreshing the JWK set fails or the key cannot be converted.
   */
  private ECPublicKey getKey(String kid, String alg) throws Exception {
    // Fast path: look up the JWK in the ConcurrentHashMap without locking.
    JWK jwk = keyCache.get(kid);
    if (jwk == null) {
      // Cache miss: synchronize to download the latest JWKSet once and evict stale rotated keys.
      synchronized (this) {
        jwk = keyCache.get(kid);
        if (jwk == null) {
          // Download the latest JWKSet and index all keys that carry a non-null key ID (kid).
          JWKSet jwkSet = loadJwkSet();
          Map<String, JWK> freshKeys = new HashMap<>();
          for (JWK key : jwkSet.getKeys()) {
            if (key.getKeyID() != null) {
              freshKeys.put(key.getKeyID(), key);
            }
          }
          // Update active keys first and then evict retired/revoked keys so lock-free readers on
          // the fast path never observe a transiently empty map during key rotation.
          keyCache.putAll(freshKeys);
          keyCache.keySet().retainAll(freshKeys.keySet());
          jwk = keyCache.get(kid);
        }
      }
    }

    // Verify that the JWK is an ECKey and that its optional algorithm metadata matches ES256.
    if (jwk instanceof ECKey) {
      if (jwk.getAlgorithm() == null || alg.equals(jwk.getAlgorithm().getName())) {
        return ((ECKey) jwk).toECPublicKey();
      }
    }
    return null;
  }

  /**
   * Parses the signed IAP JWT once, validates all required headers, timestamps (with clock-skew
   * tolerance), audience, issuer, and ES256 signature, and returns the verified email claim.
   *
   * @param jwtToken The serialized JWT assertion from the {@code x-goog-iap-jwt-assertion} header.
   * @param expectedAudience The expected {@code /projects/.../global/backendServices/...} audience.
   * @return The verified, non-empty email address from the JWT claims.
   * @throws Exception If parsing fails or any header, claim, or signature check is invalid.
   */
  String verifyAndExtractEmail(String jwtToken, String expectedAudience) throws Exception {
    logger.debug("Google-IAP: Validating JWT with expected aud {}", expectedAudience);

    // Parse the serialized JWT into its header and claims set once.
    SignedJWT signedJwt = SignedJWT.parse(jwtToken);
    JWSHeader jwsHeader = signedJwt.getHeader();

    // Enforce ES256 (ECDSA using P-256 and SHA-256) as required by Google Cloud IAP.
    Preconditions.checkNotNull(jwsHeader.getAlgorithm(), "JWT header missing algorithm (alg)");
    Preconditions.checkArgument(
        JWSAlgorithm.ES256.equals(jwsHeader.getAlgorithm()),
        "Unsupported JWT algorithm: %s",
        jwsHeader.getAlgorithm());
    Preconditions.checkNotNull(jwsHeader.getKeyID(), "JWT header missing key ID (kid)");

    // Extract the claims set and verify audience and issuer against expected IAP values.
    JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
    Preconditions.checkNotNull(claims.getAudience(), "JWT claims missing audience (aud)");
    Preconditions.checkArgument(
        claims.getAudience().contains(expectedAudience),
        "JWT audience %s does not match expected audience %s",
        claims.getAudience(),
        expectedAudience);
    Preconditions.checkArgument(
        IAP_ISSUER_URL.equals(claims.getIssuer()), "Unexpected JWT issuer: %s", claims.getIssuer());

    // Validate issue time (iat) and expiration time (exp) with a 30-second clock-skew tolerance.
    Instant now = Instant.now(clock);
    Date maxAllowedIssueTime = Date.from(now.plusSeconds(CLOCK_SKEW_SECONDS));
    Date minAllowedExpirationTime = Date.from(now.minusSeconds(CLOCK_SKEW_SECONDS));
    Preconditions.checkNotNull(claims.getIssueTime(), "JWT claims missing issue time (iat)");
    Preconditions.checkNotNull(
        claims.getExpirationTime(), "JWT claims missing expiration time (exp)");

    // Enforce timestamp bounds after applying the 30s skew window to prevent NTP drift rejections.
    Preconditions.checkArgument(
        claims.getIssueTime().before(maxAllowedIssueTime), "JWT issue time (iat) is in the future");
    Preconditions.checkArgument(
        claims.getExpirationTime().after(minAllowedExpirationTime), "JWT has expired (exp)");

    // Ensure both the subject (sub) and non-blank email claims are present.
    Preconditions.checkNotNull(claims.getSubject(), "JWT claims missing subject (sub)");
    String email = claims.getStringClaim("email");
    Preconditions.checkArgument(
        email != null && !email.trim().isEmpty(), "JWT claims missing non-empty email");

    // Resolve the ECPublicKey for the header's kid and verify the cryptographic signature.
    ECPublicKey publicKey = getKey(jwsHeader.getKeyID(), jwsHeader.getAlgorithm().getName());
    Preconditions.checkNotNull(publicKey, "No matching EC public key found for kid");
    JWSVerifier jwsVerifier = new ECDSAVerifier(publicKey);
    Preconditions.checkArgument(signedJwt.verify(jwsVerifier), "JWT cryptographic signature invalid");

    return email.trim();
  }
}
