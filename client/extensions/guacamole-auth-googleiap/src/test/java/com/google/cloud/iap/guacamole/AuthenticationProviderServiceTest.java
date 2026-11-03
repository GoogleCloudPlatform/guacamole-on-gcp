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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.iap.guacamole.user.AuthenticatedUser;
import com.google.inject.Provider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationProviderService} verifying ES256 IAP JWT validation, clock
 * skew tolerance, JWK key rotation/eviction, and error propagation.
 */
class AuthenticationProviderServiceTest {

  /** Fixed reference timestamp used across deterministic JWT tests. */
  private static final Instant FIXED_NOW = Instant.parse("2026-01-15T12:00:00Z");

  /** Test Google Cloud project number. */
  private static final String TEST_PROJECT_NUMBER = "123456789012";

  /** Test Google Cloud Load Balancer BackendService ID. */
  private static final String TEST_BACKEND_SERVICE_ID = "9876543210987654321";

  /** Expected IAP audience string constructed from the project number and BackendService ID. */
  private static final String EXPECTED_AUDIENCE =
      "/projects/" + TEST_PROJECT_NUMBER + "/global/backendServices/" + TEST_BACKEND_SERVICE_ID;

  /** Primary EC P-256 key pair used to sign valid test tokens. */
  private ECKey primaryEcKey;

  /** Reference to the currently active JWKSet returned by the service under test. */
  private AtomicReference<JWKSet> activeJwkSet;

  /** Counter tracking how many times loadJwkSet() was invoked. */
  private AtomicInteger jwkLoadCount;

  /** Mock ConfigurationService returning project number and BackendService ID. */
  private ConfigurationService mockConfigService;

  /** Service instance under test with overridden loadJwkSet(). */
  private AuthenticationProviderService authService;

  @BeforeEach
  void setUp() throws Exception {
    // Generate a fresh P-256 elliptic curve key pair for signing ES256 JWTs in tests.
    primaryEcKey =
        new ECKeyGenerator(Curve.P_256)
            .keyID("key-1")
            .algorithm(JWSAlgorithm.ES256)
            .generate();

    // Initialize the active JWKSet with the public portion of the primary EC key.
    activeJwkSet = new AtomicReference<>(new JWKSet(primaryEcKey.toPublicJWK()));
    jwkLoadCount = new AtomicInteger(0);

    // Configure the mock ConfigurationService with test project number and BackendService ID.
    mockConfigService = mock(ConfigurationService.class);
    when(mockConfigService.getProjectNumber()).thenReturn(TEST_PROJECT_NUMBER);
    when(mockConfigService.getBackendServiceId()).thenReturn(TEST_BACKEND_SERVICE_ID);

    // Provide a fresh AuthenticatedUser instance whenever Guice Provider.get() is called.
    Provider<AuthenticatedUser> userProvider = AuthenticatedUser::new;

    // Instantiate the service under test and override loadJwkSet() to avoid external network calls.
    authService =
        new AuthenticationProviderService(mockConfigService, userProvider) {
          @Override
          JWKSet loadJwkSet() {
            jwkLoadCount.incrementAndGet();
            return activeJwkSet.get();
          }
        };
    authService.setClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  /**
   * Helper method to build and sign an ES256 JWT with the specified key and claim values.
   */
  private static String createSignedJwt(
      ECKey signingKey,
      String kid,
      String audience,
      String issuer,
      Instant issuedAt,
      // Pass expiration and identity claims to construct the test token payload.
      Instant expiresAt,
      String subject,
      String email)
      throws Exception {
    // Construct the JWS header with ES256 and the specified key ID.
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build();

    // Populate the JWT claims set matching Google Cloud IAP's token structure.
    JWTClaimsSet.Builder claimsBuilder =
        new JWTClaimsSet.Builder()
            .audience(audience)
            .issuer(issuer)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .subject(subject);
    // Include the email claim only when non-null so tests can also exercise missing-email cases.
    if (email != null) {
      claimsBuilder.claim("email", email);
    }

    // Sign the JWT using the provided EC private key.
    SignedJWT signedJwt = new SignedJWT(header, claimsBuilder.build());
    signedJwt.sign(new ECDSASigner(signingKey));
    return signedJwt.serialize();
  }

  /**
   * Helper method to create Guacamole Credentials wrapping an HttpServletRequest with a JWT header.
   */
  private static Credentials createCredentialsWithToken(String jwtToken) {
    // Mock HttpServletRequest to return the given IAP JWT assertion header.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(AuthenticationProviderService.IAP_JWT_HEADER)).thenReturn(jwtToken);
    return new Credentials(null, null, request);
  }

  @Test
  void validJwtAuthenticatesAndLowercasesEmail() throws Exception {
    // Create a valid ES256 token with a mixed-case email claim.
    String token =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "Alice.Smith@Example.COM");

    // Authenticate the user and verify both the AuthenticatedUser identifier and Credentials username.
    Credentials credentials = createCredentialsWithToken(token);
    AuthenticatedUser user = authService.authenticateUser(credentials);
    assertNotNull(user);
    assertEquals("alice.smith@example.com", user.getIdentifier());
    assertEquals("alice.smith@example.com", credentials.getUsername());
  }

  @Test
  void missingRequestOrMissingHeaderThrowsInvalidCredentials() {
    // Verify that null HttpServletRequest is rejected with GuacamoleInvalidCredentialsException.
    Credentials noRequestCredentials = new Credentials(null, null, (HttpServletRequest) null);
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(noRequestCredentials));

    // Verify that a missing x-goog-iap-jwt-assertion header is rejected.
    Credentials noHeaderCredentials = createCredentialsWithToken(null);
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(noHeaderCredentials));
  }

  @Test
  void expiredJwtBeyondClockSkewIsRejected() throws Exception {
    // Create a token that expired 45 seconds ago (exceeding the 30-second clock-skew tolerance).
    String expiredToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(600),
            FIXED_NOW.minusSeconds(45),
            "accounts.google.com:111111",
            "alice@example.com");

    // Verify that the expired token is rejected.
    Credentials credentials = createCredentialsWithToken(expiredToken);
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(credentials));
  }

  @Test
  void minorClockSkewWithinToleranceSucceeds() throws Exception {
    // Create a token issued 15 seconds in the "future" relative to local clock (within 30s skew).
    String slightlyFutureIatToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.plusSeconds(15),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "bob@example.com");

    // Verify that minor iat clock skew does not cause false authentication failures.
    AuthenticatedUser userFromFutureIat =
        authService.authenticateUser(createCredentialsWithToken(slightlyFutureIatToken));
    assertEquals("bob@example.com", userFromFutureIat.getIdentifier());

    // Create a token that expired 15 seconds ago relative to local clock (within 30s skew).
    String recentlyExpiredExpToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(300),
            FIXED_NOW.minusSeconds(15),
            "accounts.google.com:111111",
            "carol@example.com");

    // Verify that minor exp clock skew within the 30-second window also succeeds.
    AuthenticatedUser userFromRecentExp =
        authService.authenticateUser(createCredentialsWithToken(recentlyExpiredExpToken));
    assertEquals("carol@example.com", userFromRecentExp.getIdentifier());
  }

  @Test
  void wrongAudienceOrIssuerIsRejected() throws Exception {
    // Create a token with an incorrect BackendService audience claim.
    String wrongAudToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            "/projects/123456789012/global/backendServices/0000000000",
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "alice@example.com");
    // Reject tokens intended for a different IAP BackendService to prevent cross-service replay.
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(createCredentialsWithToken(wrongAudToken)));

    // Create a token with an untrusted issuer claim.
    String wrongIssToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://evil.example.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "alice@example.com");
    // Reject tokens whose iss claim does not match https://cloud.google.com/iap.
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(createCredentialsWithToken(wrongIssToken)));
  }

  @Test
  void tokenSignedWithUntrustedKeyIsRejected() throws Exception {
    // Generate an attacker EC key that is not in Google's JWKSet but claims kid "key-1".
    ECKey untrustedKey =
        new ECKeyGenerator(Curve.P_256)
            .keyID("key-1")
            .algorithm(JWSAlgorithm.ES256)
            .generate();

    // Sign a token with the untrusted private key.
    String forgedToken =
        createSignedJwt(
            untrustedKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "attacker@example.com");
    // Verify that ECDSA signature verification rejects the forged token.
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(createCredentialsWithToken(forgedToken)));
  }

  @Test
  void keyRotationReloadsJwkSetAndEvictsOldKeys() throws Exception {
    // Create a valid token signed by key-1 to populate the initial JWK cache.
    String tokenKey1 =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "user1@example.com");
    // Verify initial authentication succeeds and triggers exactly one JWKSet load.
    authService.authenticateUser(createCredentialsWithToken(tokenKey1));
    assertEquals(1, jwkLoadCount.get());

    // Simulate key rotation: publish only key-2 in the JWKSet and retire key-1.
    ECKey rotatedEcKey =
        new ECKeyGenerator(Curve.P_256)
            .keyID("key-2")
            .algorithm(JWSAlgorithm.ES256)
            .generate();
    activeJwkSet.set(new JWKSet(rotatedEcKey.toPublicJWK()));

    // Create a token signed by the newly rotated key-2.
    String tokenKey2 =
        createSignedJwt(
            rotatedEcKey,
            "key-2",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:222222",
            "user2@example.com");
    // Verify cache miss on key-2 reloads the JWKSet and authenticates user2.
    AuthenticatedUser user2 = authService.authenticateUser(createCredentialsWithToken(tokenKey2));
    assertEquals("user2@example.com", user2.getIdentifier());
    assertEquals(2, jwkLoadCount.get());

    // Verify that the retired key-1 was evicted from keyCache and is no longer accepted.
    assertThrows(
        GuacamoleInvalidCredentialsException.class,
        () -> authService.authenticateUser(createCredentialsWithToken(tokenKey1)));
  }

  @Test
  void configurationServiceFailurePropagatesAsGuacamoleServerException() throws Exception {
    // Simulate a Kubernetes RBAC or GCP API failure in ConfigurationService.
    when(mockConfigService.getBackendServiceId())
        .thenThrow(new GuacamoleServerException("K8s API forbidden"));

    // Create a syntactically valid token so authentication reaches BackendService resolution.
    String validToken =
        createSignedJwt(
            primaryEcKey,
            "key-1",
            EXPECTED_AUDIENCE,
            "https://cloud.google.com/iap",
            FIXED_NOW.minusSeconds(60),
            FIXED_NOW.plusSeconds(300),
            "accounts.google.com:111111",
            "alice@example.com");
    // Verify infrastructure errors propagate as GuacamoleServerException rather than bad credentials.
    assertThrows(
        GuacamoleServerException.class,
        () -> authService.authenticateUser(createCredentialsWithToken(validToken)));
  }
}
