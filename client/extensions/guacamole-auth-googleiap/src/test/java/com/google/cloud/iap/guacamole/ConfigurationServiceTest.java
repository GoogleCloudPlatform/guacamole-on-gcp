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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigurationService} verifying static property override, thread-safe lazy
 * caching, protection against negative caching, and Kubernetes NEG annotation JSON parsing.
 */
class ConfigurationServiceTest {

  /** Mock Guacamole Environment used to supply test configuration properties. */
  private Environment mockEnvironment;

  @BeforeEach
  void setUp() throws Exception {
    // Initialize default GCP project properties on the mock Guacamole Environment.
    mockEnvironment = mock(Environment.class);
    when(mockEnvironment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_ID))
        .thenReturn("test-project");
    when(mockEnvironment.getRequiredProperty(GoogleIapGuacamoleProperties.GOOGLE_PROJECT_NUMBER))
        .thenReturn("123456789012");

    // Initialize default Kubernetes Service coordinates used for NEG status discovery.
    when(mockEnvironment.getRequiredProperty(
            GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAME))
        .thenReturn("guacamole-client-service");
    when(mockEnvironment.getRequiredProperty(
            GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_NAMESPACE))
        .thenReturn("guacamole");
    when(mockEnvironment.getRequiredProperty(
            GoogleIapGuacamoleProperties.GOOGLE_CLIENT_SERVICE_PORT))
        .thenReturn("80");
  }

  @Test
  void explicitBackendServiceIdPropertyBypassesDynamicLookup() throws Exception {
    // Configure an explicit google-backend-service-id override in guacamole.properties.
    when(mockEnvironment.getProperty(GoogleIapGuacamoleProperties.GOOGLE_BACKEND_SERVICE_ID))
        .thenReturn("5555555555555555555");

    // Track whether resolveBackendServiceId is ever invoked.
    AtomicInteger resolveCalls = new AtomicInteger(0);
    ConfigurationService service =
        new ConfigurationService(mockEnvironment) {
          @Override
          String resolveBackendServiceId(
              String projectId, String serviceName, String namespace, String servicePort) {
            resolveCalls.incrementAndGet();
            return "999999999";
          }
        };

    // Verify that the explicit override is returned and dynamic discovery was never called.
    assertEquals("5555555555555555555", service.getBackendServiceId());
    assertEquals(0, resolveCalls.get());
  }

  @Test
  void dynamicBackendServiceIdIsResolvedOnceAndCached() throws Exception {
    // Count how many times dynamic discovery is executed across multiple calls.
    AtomicInteger resolveCalls = new AtomicInteger(0);
    ConfigurationService service =
        new ConfigurationService(mockEnvironment) {
          @Override
          String resolveBackendServiceId(
              String projectId, String serviceName, String namespace, String servicePort) {
            resolveCalls.incrementAndGet();
            return "7777777777777777777";
          }
        };

    // Call getBackendServiceId() multiple times and verify resolveBackendServiceId ran only once.
    assertEquals("7777777777777777777", service.getBackendServiceId());
    assertEquals("7777777777777777777", service.getBackendServiceId());
    assertEquals("7777777777777777777", service.getBackendServiceId());
    assertEquals(1, resolveCalls.get());
  }

  @Test
  void invalidResolvedBackendServiceIdIsNotNegativeCached() throws Exception {
    // Start with an invalid ("") response, then recover to a valid BackendService ID on retry.
    AtomicReference<String> simulatedResponse = new AtomicReference<>("");
    AtomicInteger resolveCalls = new AtomicInteger(0);
    // Override resolveBackendServiceId to return the current simulatedResponse value.
    ConfigurationService service =
        new ConfigurationService(mockEnvironment) {
          @Override
          String resolveBackendServiceId(
              String projectId, String serviceName, String namespace, String servicePort) {
            resolveCalls.incrementAndGet();
            return simulatedResponse.get();
          }
        };

    // Verify that empty "" and "0" responses throw GuacamoleServerException and are not cached.
    assertThrows(GuacamoleServerException.class, service::getBackendServiceId);
    simulatedResponse.set("0");
    assertThrows(GuacamoleServerException.class, service::getBackendServiceId);

    // Verify that a subsequent call succeeds and caches once the backend returns a valid ID.
    simulatedResponse.set("8888888888888888888");
    assertEquals("8888888888888888888", service.getBackendServiceId());
    assertEquals("8888888888888888888", service.getBackendServiceId());
    assertEquals(3, resolveCalls.get());
  }

  @Test
  void parseNegNameFromAnnotationExtractsValidNegAndRejectsInvalidInputs() throws Exception {
    // Verify parsing a realistic GKE Ingress cloud.google.com/neg-status JSON annotation.
    String validNegJson =
        "{\"network_endpoint_groups\":{\"80\":\"k8s1-12345678-guacamole-guacamole-client-service-80-abcdef12\"},"
            + "\"zones\":[\"us-central1-a\",\"us-central1-b\"]}";
    assertEquals(
        "k8s1-12345678-guacamole-guacamole-client-service-80-abcdef12",
        ConfigurationService.parseNegNameFromAnnotation(validNegJson, "80"));

    // Verify null, blank, and malformed JSON annotations are rejected with GuacamoleException.
    assertThrows(
        GuacamoleException.class,
        () -> ConfigurationService.parseNegNameFromAnnotation(null, "80"));
    assertThrows(
        GuacamoleException.class,
        () -> ConfigurationService.parseNegNameFromAnnotation("   ", "80"));
    assertThrows(
        GuacamoleException.class,
        () -> ConfigurationService.parseNegNameFromAnnotation("{not-json", "80"));

    // Verify missing network_endpoint_groups object or unmapped service port keys are rejected.
    assertThrows(
        GuacamoleException.class,
        () -> ConfigurationService.parseNegNameFromAnnotation("{\"zones\":[]}", "80"));
    assertThrows(
        GuacamoleException.class,
        () -> ConfigurationService.parseNegNameFromAnnotation(validNegJson, "8080"));
  }
}
