# Guacamole Client & Google IAP Extension (`client/`)

This directory contains the Docker build context, Kubernetes manifests, and custom Java 21 authentication extension (`guacamole-auth-googleiap`) for the `guacamole-client-iap` container image based on **Apache Guacamole 1.6.0** (`guacamole/guacamole:1.6.0`).

---

## Directory Layout

| Path | Description |
| :--- | :--- |
| [`Dockerfile`](Dockerfile) | Multi-stage Docker build: compiles `guacamole-auth-googleiap` with `maven:3-eclipse-temurin-21` and copies the extension JAR and runtime dependencies into `guacamole/guacamole:1.6.0` (`/etc/guacamole`) with `guacamole:guacamole` (`UID 1001`) ownership. |
| [`cloudbuild.yaml`](cloudbuild.yaml) | Google Cloud Build configuration building `guacamole-client-iap` with BuildKit (`DOCKER_BUILDKIT=1`). |
| [`bin/build-googleiap.sh`](bin/build-googleiap.sh) | Build helper invoked inside the Docker builder stage (`mvn -B package`). |
| [`extensions/guacamole-auth-googleiap/`](extensions/guacamole-auth-googleiap/pom.xml) | Maven project for the Google Cloud Identity-Aware Proxy (IAP) Guacamole 1.6.0 authentication provider. |
| [`kubernetes-manifests/`](kubernetes-manifests/) | Kubernetes manifests for the `guacamole` Namespace, `svc-guacamole` ServiceAccount, RBAC `Role`/`RoleBinding`, `Deployment` (with Cloud SQL Auth Proxy sidecar), `Service`, `BackendConfig`, `ManagedCertificate`, and `Ingress`. |

---

## Google IAP Authentication Extension (`guacamole-auth-googleiap`)

### Key Classes
- [`GoogleIapAuthenticationProvider.java`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/GoogleIapAuthenticationProvider.java): Entry point registered in [`guac-manifest.json`](extensions/guacamole-auth-googleiap/src/main/resources/guac-manifest.json) (`namespace: "google-iap"`). Creates the Guice injector.
- [`GoogleIapAuthenticationProviderModule.java`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/GoogleIapAuthenticationProviderModule.java): Binds [`ConfigurationService`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/ConfigurationService.java) and [`AuthenticationProviderService`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/AuthenticationProviderService.java) in `Scopes.SINGLETON`.
- [`ConfigurationService.java`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/ConfigurationService.java): Resolves the Google Cloud project ID, project number, and Load Balancer `BackendService` ID from `GOOGLE_*` environment variables (injected via the `guacamole-client-settings` ConfigMap) or `guacamole.properties`. Supports an optional static `GOOGLE_BACKEND_SERVICE_ID` (`google-backend-service-id`) override; otherwise lazily resolves the ID once via the Kubernetes `cloud.google.com/neg-status` Service annotation and Google Cloud Compute Engine `BackendServices.get` API and caches it in memory.
- [`AuthenticationProviderService.java`](extensions/guacamole-auth-googleiap/src/main/java/com/google/cloud/iap/guacamole/AuthenticationProviderService.java): Extracts the `x-goog-iap-jwt-assertion` header, caches Google's public EC keys (`https://www.gstatic.com/iap/verify/public_key-jwk`) in a thread-safe `ConcurrentHashMap` with automatic eviction of rotated keys on refresh, validates `ES256` signature and claims (`aud`, `iss`, `iat`, `exp` with 30s clock-skew tolerance, `sub`, `email`) in a single parse pass, and normalizes the authenticated user's email to lowercase.

### Configuration Settings (`guacamole-client-settings` ConfigMap / `guacamole.properties`)

| Environment Variable | Property Name | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `GOOGLE_PROJECT_ID` | `google-project-id` | Yes | — | Google Cloud Project ID hosting the GKE cluster and Load Balancer. |
| `GOOGLE_PROJECT_NUMBER` | `google-project-number` | Yes | — | Numeric Google Cloud Project Number used in the IAP JWT `aud` claim. |
| `GOOGLE_CLIENT_SERVICE_NAME` | `google-client-service-name` | No | `guacamole-client-service` | Kubernetes `Service` name. |
| `GOOGLE_CLIENT_SERVICE_NAMESPACE` | `google-client-service-namespace` | No | `guacamole` | Kubernetes `Namespace`. |
| `GOOGLE_CLIENT_SERVICE_PORT` | `google-client-service-port` | No | `80` | Kubernetes `Service` port mapped to the Ingress NEG. |
| `GOOGLE_BACKEND_SERVICE_ID` | `google-backend-service-id` | No | — | Optional explicit numeric BackendService ID override; bypasses dynamic K8s/GCP API discovery when set. |

### Running Unit Tests & License Checks Locally
To run the JUnit 5 test suite ([`AuthenticationProviderServiceTest.java`](extensions/guacamole-auth-googleiap/src/test/java/com/google/cloud/iap/guacamole/AuthenticationProviderServiceTest.java) and [`ConfigurationServiceTest.java`](extensions/guacamole-auth-googleiap/src/test/java/com/google/cloud/iap/guacamole/ConfigurationServiceTest.java)) and Apache RAT license header check on a machine with JDK 21 and Maven installed:

```bash
cd client/extensions/guacamole-auth-googleiap
mvn -B clean test package
```
