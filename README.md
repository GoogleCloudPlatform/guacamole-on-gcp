# Apache Guacamole on Google Cloud (GKE Autopilot + Identity-Aware Proxy)

This repository provides a reference architecture and end-to-end deployment automation for running **Apache Guacamole 1.6.0**—a clientless, browser-based remote desktop (RDP/VNC) and SSH gateway—on **Google Kubernetes Engine (GKE) Autopilot**, protected by **Google Cloud Identity-Aware Proxy (IAP)** and backed by **Cloud SQL for MySQL 8.0**.

---

## Architecture Overview

Rather than exposing SSH (`22`) or RDP (`3389`) ports on virtual machines to the public internet, users connect over HTTPS to a Google Cloud Global External Application Load Balancer secured by **Identity-Aware Proxy (IAP)**.

```mermaid
flowchart TB
    User["User Browser"]
    subgraph GCP["Google Cloud Project (Provisioned by terraform/)"]
        GLB["Global External Application Load Balancer<br/>(Static IP + Managed Certificate + IAP)"]
        ComputeAPI["Compute Engine API<br/>(BackendServices.get)"]
        AR["Artifact Registry<br/>(guac-repo)"]

        subgraph VPC["Custom VPC (guacamole-vpc / 10.10.0.0/24)"]
            subgraph GKE["GKE Autopilot Private Cluster (guacamole-gke)"]
                K8sAPI["Kubernetes API Server<br/>(Service NEG Annotation)"]
                subgraph PodClient["Pod: guacamole-client (K8s SA: svc-guacamole)"]
                    InitDB["InitContainers: copy-schema + init-db (mysql:8.0)<br/>(Auto-applies 001-create-schema.sql & insert-admin-user.sql)"]
                    GuacClient["Container: guacamole-client-iap (Guacamole 1.6.0)<br/>(guacamole-auth-googleiap + guacamole-auth-jdbc-mysql)"]
                    SQLProxy["Native Sidecar: cloud-sql-proxy:2.14.1<br/>(127.0.0.1:3306 via Workload Identity)"]
                end
                subgraph PodServer["Pod: guacamole-server"]
                    Guacd["Container: guacamole/guacd:1.6.0<br/>(Port 4822)"]
                end
            end

            CloudSQL[("Cloud SQL for MySQL 8.0<br/>(Database: guacamole, Private IP)")]
            TargetVMs["Target Compute Engine VMs<br/>(SSH / RDP Endpoints in VPC)"]
        end
    end

    User -->|"HTTPS + Google IAP Login"| GLB
    GLB -->|"x-goog-iap-jwt-assertion<br/>(Container-Native NEG)"| GuacClient
    GuacClient -.->|"1. First login only: Read NEG name"| K8sAPI
    GuacClient -.->|"2. First login only: Resolve BackendService ID"| ComputeAPI
    InitDB -.->|"Pod startup: Bootstrap schema & admin"| SQLProxy
    GuacClient -->|"JDBC (127.0.0.1:3306)"| SQLProxy
    SQLProxy -->|"mTLS + IAM (roles/cloudsql.client)"| CloudSQL
    GuacClient -->|"Guacamole Protocol (4822)"| Guacd
    Guacd -->|"SSH (22) / RDP (3389)"| TargetVMs
```

### How Authentication & Database Connectivity Work
1. **Identity-Aware Proxy (IAP) Verification (`guacamole-auth-googleiap`)**:
   - When an authorized user accesses Guacamole through the external Load Balancer, IAP authenticates their Google identity and attaches a signed `ES256` JWT in the `x-goog-iap-jwt-assertion` HTTP header.
   - On the first authentication request, the custom [`guacamole-auth-googleiap`](client/README.md) extension reads the `cloud.google.com/neg-status` annotation on `guacamole-client-service` via the in-cluster Kubernetes API, resolves the numeric Load Balancer `BackendService` ID via the Google Cloud Compute Engine API (`compute.backendServices.get`), and caches that ID in memory in a thread-safe singleton.
   - Using Google's public JWK set (`https://www.gstatic.com/iap/verify/public_key-jwk`), the extension verifies the JWT's `ES256` cryptographic signature, audience (`/projects/<PROJECT_NUMBER>/global/backendServices/<BACKEND_SERVICE_ID>`), issuer (`https://cloud.google.com/iap`), and timestamps (with 30-second clock-skew tolerance), and normalizes the verified email address to lowercase.
2. **Automatic Database Initialization & Connection Authorization (`guacamole-auth-jdbc-mysql` + Cloud SQL Auth Proxy)**:
   - On Pod startup, the **Cloud SQL Auth Proxy v2** (`gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.14.1`) native sidecar starts first and authenticates to **Cloud SQL for MySQL 8.0** over Private IP via **GKE Workload Identity** (`roles/cloudsql.client`).
   - Next, the `copy-schema` and `init-db` `initContainers` extract `/opt/guacamole/extensions/guacamole-auth-jdbc/mysql/schema/001-create-schema.sql` directly from the `guacamole-client-iap` container image, apply the schema if `guacamole_entity` does not yet exist, and idempotently execute `insert-admin-user.sql` to grant the deploying Google identity `ADMINISTER` and `AUDIT` permissions.
   - Once initialized, Guacamole's MySQL extension trusts the email identity verified by the IAP extension and queries `127.0.0.1:3306` to load that user's authorized SSH/RDP connections and system permissions.

---

## Repository Structure

| Path | Description |
| :--- | :--- |
| [`terraform/`](terraform/README.md) | Single-stage Terraform configuration provisioning VPC networking, GKE Autopilot, Cloud SQL for MySQL 8.0, Artifact Registry, IAM / Workload Identity bindings, and rendered configuration files. |
| [`client/`](client/README.md) | Custom `guacamole-client-iap` Docker image (`guacamole/guacamole:1.6.0`), the Java 21 `guacamole-auth-googleiap` Maven extension, and Kubernetes manifests for the client Pod, Service, Ingress, ManagedCertificate, and BackendConfig. |
| [`server/`](server/README.md) | Kubernetes Deployment and Service manifests for `guacamole-server` (`guacamole/guacd:1.6.0`). |
| [`bin/read-tf-output.sh`](bin/read-tf-output.sh) | Sourced helper script that exports Terraform outputs (`PROJECT_ID`, `GKE_CLUSTER`, `ARTIFACT_REGISTRY_REPO`, `GUACAMOLE_URL`, etc.) as shell environment variables. |
| [`kustomization.yaml`](kustomization.yaml) | Root Kustomize manifest assembling the `guacamole` Namespace, `svc-guacamole` ServiceAccount, ConfigMap, Secrets (`db-user-pass` and `guacamole-db-init`), and client/server workloads. |
| [`skaffold.yaml`](skaffold.yaml) | Skaffold `v4beta5` pipeline for building `guacamole-client-iap` (`linux/amd64`) locally or via Google Cloud Build (`-p cloudbuild`) and deploying via Kustomize. |

---

## Prerequisites

1. A **Google Cloud project** with billing enabled.
2. **Identity-Aware Proxy (IAP) OAuth Configuration**:
   - **Internal applications (within a Google Cloud Organization):** No OAuth Consent Screen or OAuth client configuration is required. [`guacamole-client.backendconfig.yaml`](client/kubernetes-manifests/guacamole-client.backendconfig.yaml) enables IAP with `iap.enabled: true` (omitting `oauthclientCredentials`), which automatically uses IAP's Google-managed OAuth client for users within your organization.
   - **External applications (outside a Google Cloud Organization or allowing external users):** If your project is not part of a Google Cloud Organization or you need to grant access to external users, configure an External OAuth Consent Screen and custom OAuth client credentials by following the Google Cloud documentation on [Using custom OAuth clients with IAP](https://cloud.google.com/iap/docs/auth-howto) and [Enabling IAP for GKE (`BackendConfig` with `oauthclientCredentials`)](https://cloud.google.com/iap/docs/enabling-kubernetes-howto).
3. Local CLI tools installed:
   - `gcloud` (Google Cloud SDK, authenticated via `gcloud auth login` and `gcloud auth application-default login`)
   - `terraform` (`>= 1.5.0`)
   - `kubectl` and `gke-gcloud-auth-plugin`
   - `skaffold` (`>= 2.0`)

---

## Quickstart Deployment Guide

### Step 1: Provision Google Cloud Infrastructure (Single-Stage Terraform)
Run Terraform inside [`terraform/`](terraform/README.md) to enable required APIs, create the VPC, GKE Autopilot cluster, Cloud SQL MySQL 8.0 instance and `guacamole` database, Artifact Registry repository, and Workload Identity IAM bindings, and render the local configuration files:

```bash
terraform -chdir=terraform init
terraform -chdir=terraform apply -var="project_id=YOUR_PROJECT_ID"
```

### Step 2: Export Environment Variables from Terraform Outputs
From the repository root, source [`bin/read-tf-output.sh`](bin/read-tf-output.sh):

```bash
source bin/read-tf-output.sh
```

### Step 3: Connect to the GKE Autopilot Cluster & Deploy with Skaffold
Fetch cluster credentials and deploy Guacamole using Skaffold (using `-p cloudbuild` to build the image in Google Cloud Build, or omit `-p cloudbuild` to build with a local Docker daemon). The `guacamole-client` Pod's `initContainers` will automatically initialize the Cloud SQL MySQL schema and admin user on first startup:

```bash
gcloud container clusters get-credentials "${GKE_CLUSTER}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}"

skaffold run -p cloudbuild --default-repo="${ARTIFACT_REGISTRY_REPO}"
```

### Step 4: Access the Guacamole Web UI
Wait 10–15 minutes for the GKE Ingress controller to provision the Global External Application Load Balancer and for the Google-managed SSL certificate (`guacamole-client-cert`) to reach `Active` status:

```bash
kubectl get managedcertificate guacamole-client-cert -n guacamole
```

Once the certificate status is `Active`, open `${GUACAMOLE_URL}` in your browser and sign in with your Google identity.

---

## Teardown

To delete the Kubernetes workloads and tear down all Google Cloud infrastructure cleanly:

```bash
skaffold delete
terraform -chdir=terraform destroy -var="project_id=${PROJECT_ID}"
```
