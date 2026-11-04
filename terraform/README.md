# Terraform Infrastructure (`terraform/`)

This directory contains the single-stage Terraform configuration that provisions all Google Cloud infrastructure, IAM / Workload Identity bindings, and rendered Kubernetes/Guacamole configuration files for `guacamole-on-gcp`.

---

## File Overview

| File | Purpose |
| :--- | :--- |
| [`versions.tf`](versions.tf) | Declares `required_version = ">= 1.5.0"` and pins the `google` (`>= 6.10.0, < 7.0.0`), `random` (`>= 3.6.0`), `local` (`>= 2.5.0`), and `external` (`>= 2.3.0`) providers. |
| [`variables.tf`](variables.tf) | Input variables for project ID, region, zone, VPC name, Cloud SQL settings, external domain (`sslip.io` by default), deletion protection, and required GCP APIs. |
| [`main.tf`](main.tf) | Configures the `google` provider, resolves the operator's admin email (from `var.admin_email`, ADC OpenID userinfo, or active `gcloud` / Cloud Shell configuration), computes the `sslip.io` domain in pure HCL, generates random DB passwords, and enables required project APIs via `terraform-google-modules/project-factory/google//modules/project_services`. |
| [`compute.tf`](compute.tf) | Provisions the custom VPC (`guacamole-vpc`), subnet (`10.10.0.0/24`), Cloud Router + Cloud NAT, Private Service Access IP range, IAP/guacd firewall rules, and global static IP (`guacamole-external`). |
| [`gke.tf`](gke.tf) | Provisions the private GKE Autopilot cluster (`guacamole-gke`) configured with the least-privilege `svc-gke-node` service account and the managed GKE Secret Manager CSI add-on (`secret_manager_config`). |
| [`cloudsql.tf`](cloudsql.tf) | Provisions the Private Service Access peering (`deletion_policy = "ABANDON"`), the private Cloud SQL for MySQL 8.0 instance, the `guacamole` database (`utf8mb4`), and the `guac-db-user` MySQL user. |
| [`secrets.tf`](secrets.tf) | Stores the Cloud SQL password (`guacamole-db-password`) and admin bootstrap SQL (`guacamole-db-init-sql`) in Google Secret Manager and grants `roles/secretmanager.secretAccessor` to `svc-guacamole`. |
| [`artifactrepo.tf`](artifactrepo.tf) | Creates the `guac-repo` Docker repository in Artifact Registry, the `<project_id>_cloudbuild` GCS staging bucket for Cloud Build, and IAM bindings for `svc-gke-node` and the Compute Engine default service account. |
| [`clouddeploy.tf`](clouddeploy.tf) | Provisions the `guacamole-pipeline` Google Cloud Deploy delivery pipeline, `guacamole-gke` GKE target, `svc-clouddeploy` execution service account, and `<project_id>_clouddeploy` artifact bucket. |
| [`iam.tf`](iam.tf) | Creates `svc-guacamole` and `svc-gke-node`, defines the custom `iap_jwt_verifier` role (`compute.backendServices.get`), grants `roles/cloudsql.client` to `svc-guacamole`, and binds `roles/iam.workloadIdentityUser` to `serviceAccount:<project_id>.svc.id.goog[guacamole/svc-guacamole]`. |
| [`templates.tf`](templates.tf) | Renders non-sensitive Kustomize inputs (`client/client-settings.properties`, `guacamole-client.image-patch.yaml`, `guacamole-client.managedcert.yaml`, `guacamole-client.secretproviderclass.yaml`, and `guacamole-client.serviceaccount.yaml`) from [`templates/`](templates/). |
| [`outputs.tf`](outputs.tf) | Exports project, admin email (`admin_email`), network, GKE, Cloud SQL (`db_address`, `cloud_sql_instance_name`, `cloud_sql_connection_name`), Artifact Registry (`artifact_registry_repo`), Cloud Deploy (`clouddeploy_pipeline`), and URL outputs. |

---

## Input Variables

| Variable | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `project_id` | `string` | *(Required)* | Target Google Cloud project ID. |
| `admin_email` | `string` | `""` | Google account email granted IAP access and Guacamole admin permissions (defaults to the email from Application Default Credentials or the active `gcloud` / Cloud Shell account). |
| `region` | `string` | `"us-central1"` | Google Cloud region for GKE, Cloud SQL, and Artifact Registry. |
| `zone` | `string` | `"us-central1-c"` | Google Cloud zone for zonal resources. |
| `network_name` | `string` | `"guacamole-vpc"` | Custom VPC network name. |
| `db_name` | `string` | `"guacamole-mysql"` | Base name for the Cloud SQL MySQL instance. |
| `db_username` | `string` | `"guac-db-user"` | Application MySQL username. |
| `external_url` | `string` | `"sslip.io"` | External domain for Guacamole; defaults to `<LB_IP_WITH_DASHES>.sslip.io`. |
| `nwr_master_node` | `string` | `"172.16.0.32/28"` | CIDR range for the GKE private control plane. |
| `deletion_protection` | `bool` | `false` | Whether to enable Terraform deletion protection on GKE and Cloud SQL. |
| `required_apis` | `list(string)` | *(See [`variables.tf`](variables.tf))* | Google Cloud APIs activated automatically by `module.project-services`. |

---

## Rendered Files (`templates.tf`)

Running `terraform apply` generates the following non-sensitive git-ignored files in the workspace (all secrets are stored in Google Secret Manager via [`secrets.tf`](secrets.tf)):
- `../client/client-settings.properties`: Consumed by Kustomize to create the `guacamole-client-settings` ConfigMap (`WEBAPP_CONTEXT=ROOT`, `ENABLE_ENVIRONMENT_PROPERTIES=true`, `MYSQL_USERNAME`, `GOOGLE_PROJECT_ID`, `GOOGLE_PROJECT_NUMBER`, `GOOGLE_CLIENT_SERVICE_*`, `CLOUD_SQL_CONNECTION_NAME`, `MYSQL_HOSTNAME=127.0.0.1`, `REMOTE_IP_VALVE_ENABLED=true`, `REMOTE_IP_VALVE_INTERNAL_PROXIES`, `LOG_LEVEL=info`).
- `../client/kubernetes-manifests/guacamole-client.image-patch.yaml`: Kustomize strategic merge patch setting the Artifact Registry image URI on `guacamole-client` and `copy-schema` so `kubectl apply -k .` works independently without Skaffold.
- `../client/kubernetes-manifests/guacamole-client.managedcert.yaml`: GKE `ManagedCertificate` manifest for the resolved external domain.
- `../client/kubernetes-manifests/guacamole-client.secretproviderclass.yaml`: `SecretProviderClass` (`provider: gke`) mapping `guacamole-db-password` and `guacamole-db-init-sql` from Google Secret Manager into `/secrets` via the GKE Secret Manager CSI add-on (`secrets-store-gke.csi.k8s.io`).
- `../client/kubernetes-manifests/guacamole-client.serviceaccount.yaml`: Kubernetes `ServiceAccount` (`svc-guacamole`) annotated with `iam.gke.io/gcp-service-account`.
