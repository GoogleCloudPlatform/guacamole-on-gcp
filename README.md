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
        SM["Google Secret Manager<br/>(guacamole-db-password & guacamole-db-init-sql)"]
        AR["Artifact Registry & Cloud Deploy<br/>(guac-repo / guacamole-pipeline)"]

        subgraph VPC["Custom VPC (guacamole-vpc / 10.10.0.0/24)"]
            subgraph GKE["GKE Autopilot Private Cluster (guacamole-gke)"]
                K8sAPI["Kubernetes API Server<br/>(Service NEG Annotation)"]
                subgraph PodClient["Pod: guacamole-client (K8s SA: svc-guacamole)"]
                    InitDB["InitContainers: copy-schema + init-db (mysql:8.0)<br/>(Fetches secrets via Workload Identity & bootstraps schema)"]
                    GuacClient["Container: guacamole-client-iap (Guacamole 1.6.0)<br/>(guacamole-auth-googleiap + guacamole-auth-jdbc-mysql)"]
                    SQLProxy["Native Sidecar: cloud-sql-proxy:2.14.1<br/>(127.0.0.1:3306 via Workload Identity)"]
                end
                subgraph PodServer["Pod(s): guacamole-server (HPA: 1–10 Replicas)"]
                    Guacd["Container: guacamole/guacd:1.6.0<br/>(Port 4822 + preStop Session Drain)"]
                end
            end

            CloudSQL[("Cloud SQL for MySQL 8.0<br/>(Database: guacamole, Private IP)")]
            TargetVMs["Target Compute Engine VMs<br/>(SSH / RDP / VNC Endpoints in VPC)"]
        end
    end

    User -->|"HTTPS + Google IAP Login"| GLB
    GLB -->|"x-goog-iap-jwt-assertion<br/>(Container-Native NEG)"| GuacClient
    GuacClient -.->|"1. First login only: Read NEG name"| K8sAPI
    GuacClient -.->|"2. First login only: Resolve BackendService ID"| ComputeAPI
    InitDB -.->|"Pod startup: Read secrets via Workload Identity"| SM
    InitDB -.->|"Pod startup: Bootstrap schema & admin"| SQLProxy
    GuacClient -->|"JDBC (127.0.0.1:3306)"| SQLProxy
    SQLProxy -->|"mTLS + IAM (roles/cloudsql.client)"| CloudSQL
    GuacClient -->|"Guacamole Protocol (4822)"| Guacd
    Guacd -->|"SSH (22) / RDP (3389) / VNC (5901)"| TargetVMs
```

### How Authentication & Database Connectivity Work
1. **Identity-Aware Proxy (IAP) Verification (`guacamole-auth-googleiap`)**:
   - When an authorized user accesses Guacamole through the external Load Balancer, IAP authenticates their Google identity and attaches a signed `ES256` JWT in the `x-goog-iap-jwt-assertion` HTTP header.
   - On the first authentication request, the custom [`guacamole-auth-googleiap`](client/README.md) extension reads the `cloud.google.com/neg-status` annotation on `guacamole-client-service` via the in-cluster Kubernetes API, resolves the numeric Load Balancer `BackendService` ID via the Google Cloud Compute Engine API (`compute.backendServices.get`), and caches that ID in memory in a thread-safe singleton.
   - Using Google's public JWK set (`https://www.gstatic.com/iap/verify/public_key-jwk`), the extension verifies the JWT's `ES256` cryptographic signature, audience (`/projects/<PROJECT_NUMBER>/global/backendServices/<BACKEND_SERVICE_ID>`), issuer (`https://cloud.google.com/iap`), and timestamps (with 30-second clock-skew tolerance), and normalizes the verified email address to lowercase.
2. **Automatic Database Initialization & Secret Management (`guacamole-auth-jdbc-mysql` + Google Secret Manager + Cloud SQL Auth Proxy)**:
   - Sensitive values—the Cloud SQL password (`guacamole-db-password`) and the admin bootstrap SQL script (`guacamole-db-init-sql`)—are stored directly in **Google Secret Manager** by Terraform rather than written to local files or Kubernetes `Secret` manifests.
   - On Pod startup, the **Cloud SQL Auth Proxy v2** (`gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.14.1`) native sidecar starts first and authenticates to **Cloud SQL for MySQL 8.0** over Private IP via **GKE Workload Identity** (`roles/cloudsql.client`).
   - Next, the `copy-schema` and `init-db` `initContainers` extract `/opt/guacamole/extensions/guacamole-auth-jdbc/mysql/schema/001-create-schema.sql` from the `guacamole-client-iap` container image, retrieve `guacamole-db-password` and `guacamole-db-init-sql` from **Google Secret Manager** via Workload Identity (`roles/secretmanager.secretAccessor`) into a RAM-backed `emptyDir` (`medium: Memory`), apply the schema if `guacamole_entity` does not yet exist, and idempotently execute `insert-admin-user.sql` to grant the deploying Google identity `ADMINISTER` and `AUDIT` permissions.
   - Once initialized, Guacamole's MySQL extension trusts the email identity verified by the IAP extension and queries `127.0.0.1:3306` to load that user's authorized SSH/RDP/VNC connections and system permissions.

---

## Repository Structure

| Path | Description |
| :--- | :--- |
| [`terraform/`](terraform/README.md) | Single-stage Terraform configuration provisioning VPC networking, GKE Autopilot, Cloud SQL for MySQL 8.0, Google Secret Manager secrets, Artifact Registry, Google Cloud Deploy pipeline/target, IAM / Workload Identity bindings, and rendered configuration files. |
| [`client/`](client/README.md) | Custom `guacamole-client-iap` Docker image (`guacamole/guacamole:1.6.0`), the Java 21 `guacamole-auth-googleiap` Maven extension, and Kubernetes manifests for the client Pod, Service, Ingress, ManagedCertificate, and BackendConfig. |
| [`server/`](server/README.md) | Kubernetes Deployment, Service, and `HorizontalPodAutoscaler` manifests for `guacamole-server` (`guacamole/guacd:1.6.0`). |
| [`bin/read-tf-output.sh`](bin/read-tf-output.sh) | Sourced helper script that exports Terraform outputs (`PROJECT_ID`, `GKE_CLUSTER`, `ARTIFACT_REGISTRY_REPO`, `CLOUDDEPLOY_PIPELINE`, `GUACAMOLE_URL`, `ADMIN_EMAIL`, etc.) as shell environment variables. |
| [`bin/create-test-vms.sh`](bin/create-test-vms.sh) | Helper script that provisions a Windows RDP test VM and a Linux VNC test VM, resets the Windows admin password, and generates a Guacamole batch-import JSON file (`test-connections.json`). |
| [`kustomization.yaml`](kustomization.yaml) | Root Kustomize manifest assembling the `guacamole` Namespace, `svc-guacamole` ServiceAccount, ConfigMap, Artifact Registry image patch, and client/server workloads for direct deployment via `kubectl apply -k .` or Google Cloud Deploy. |
| [`skaffold.yaml`](skaffold.yaml) | Minimal manifest/deploy configuration retained solely for Google Cloud Deploy release rendering. |

---

## Prerequisites

1. A **Google Cloud project** with billing enabled.
2. **Identity-Aware Proxy (IAP) OAuth Configuration**:
   - **Internal applications (within a Google Cloud Organization):** No OAuth Consent Screen or OAuth client configuration is required. [`guacamole-client.backendconfig.yaml`](client/kubernetes-manifests/guacamole-client.backendconfig.yaml) enables IAP with `iap.enabled: true` (omitting `oauthclientCredentials`), which automatically uses IAP's Google-managed OAuth client for users within your organization.
   - **External applications (outside a Google Cloud Organization or allowing external users):** If your project is not part of a Google Cloud Organization or you need to grant access to external users, configure an External OAuth Consent Screen and custom OAuth client credentials by following the Google Cloud documentation on [Creating a custom OAuth configuration for IAP](https://docs.cloud.google.com/iap/docs/custom-oauth-configuration) and [Enabling IAP for GKE (`BackendConfig` with `oauthclientCredentials`)](https://docs.cloud.google.com/iap/docs/enabling-kubernetes-howto).
3. Local CLI tools installed:
   - `gcloud` (Google Cloud SDK, authenticated via `gcloud auth login` and `gcloud auth application-default login`)
   - `terraform` (`>= 1.5.0`)
   - `kubectl` and `gke-gcloud-auth-plugin`

---

## Quickstart Deployment Guide

### Step 1: Provision Google Cloud Infrastructure (Single-Stage Terraform)
Run Terraform inside [`terraform/`](terraform/README.md) to enable required APIs, create the VPC, GKE Autopilot cluster, Cloud SQL MySQL 8.0 instance and `guacamole` database, Google Secret Manager secrets (`guacamole-db-password` and `guacamole-db-init-sql`), Artifact Registry repository, Google Cloud Deploy pipeline (`guacamole-pipeline`), and Workload Identity IAM bindings, and render the non-sensitive local configuration files:

```bash
terraform -chdir=terraform init
terraform -chdir=terraform apply -var="project_id=YOUR_PROJECT_ID"
```

> **Note:** Terraform uses your **Application Default Credentials (ADC)** (`gcloud auth application-default login`, or the built-in Cloud Shell credentials) to authenticate to Google Cloud, and automatically detects your email address from ADC or your active `gcloud` CLI / Cloud Shell session for IAP access and the initial Guacamole admin user. To grant admin access to a different identity, pass `-var="admin_email=you@example.com"` to `terraform apply`.

### Step 2: Export Environment Variables from Terraform Outputs
From the repository root, source [`bin/read-tf-output.sh`](bin/read-tf-output.sh):

```bash
source bin/read-tf-output.sh
```

### Step 3: Build the `guacamole-client-iap` Image & Deploy to GKE Autopilot
Build and push the `guacamole-client-iap` container image using Google Cloud Build:

```bash
gcloud builds submit ./client \
  --tag "${ARTIFACT_REGISTRY_REPO}/guacamole-client-iap:latest" \
  --project="${PROJECT_ID}"
```

Then deploy the Kubernetes manifests using **either** `kubectl apply -k .` **or** **Google Cloud Deploy**:

#### Option A: Direct Deployment with `kubectl apply -k .`
```bash
gcloud container clusters get-credentials "${GKE_CLUSTER}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}"

kubectl apply -k .
```

#### Option B: Managed Release with Google Cloud Deploy
```bash
gcloud deploy releases create "rel-$(date +%Y%m%d-%H%M%S)" \
  --delivery-pipeline="${CLOUDDEPLOY_PIPELINE}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --images="${ARTIFACT_REGISTRY_REPO}/guacamole-client-iap=${ARTIFACT_REGISTRY_REPO}/guacamole-client-iap:latest"
```

The `guacamole-client` Pod's `initContainers` will automatically fetch the database password and admin bootstrap SQL from Google Secret Manager and initialize the Cloud SQL MySQL schema on first startup.

### Step 4: Access the Guacamole Web UI
Wait 10–15 minutes for the GKE Ingress controller to provision the Global External Application Load Balancer and for the Google-managed SSL certificate (`guacamole-client-cert`) to reach `Active` status:

```bash
kubectl get managedcertificate guacamole-client-cert -n guacamole
```

Once the certificate status is `Active`, open `${GUACAMOLE_URL}` in your browser and sign in with your Google identity.

### Step 5 (Optional): Create Test VMs & Batch-Import Connections
To provision a Windows test VM (`windows-vm` over RDP with NLA) and a Linux desktop test VM (`linux-vm` over VNC with XFCE4 + TigerVNC) and generate a [Guacamole batch-import JSON file](https://guacamole.apache.org/doc/gug/batch-import.html) (`test-connections.json`) under `ROOT/Test Group`:

```bash
./bin/create-test-vms.sh
```

Follow the on-screen instructions printed by the script to import `test-connections.json` under **Settings** → **Connections** → **Import**.

---

## Teardown

To delete any test VMs, Kubernetes workloads, and Google Cloud infrastructure cleanly:

```bash
gcloud compute instances delete windows-vm linux-vm --zone="${ZONE}" --project="${PROJECT_ID}" --quiet || true
kubectl delete -k . --ignore-not-found
terraform -chdir=terraform destroy -var="project_id=${PROJECT_ID}"
```
