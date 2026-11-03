# 
# Copyright 2020 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Grant the deploying operator permission to SSH into VMs through Identity-Aware Proxy TCP forwarding.
resource "google_project_iam_member" "iap-tcp-user" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "user:${data.google_client_openid_userinfo.me.email}"
}

# Grant the deploying operator permission to access the Guacamole web UI through Identity-Aware Proxy.
resource "google_project_iam_member" "iap-web-user" {
  project = var.project_id
  role    = "roles/iap.httpsResourceAccessor"
  member  = "user:${data.google_client_openid_userinfo.me.email}"
}

# Dedicated GCP Service Account bound to the Kubernetes svc-guacamole ServiceAccount via Workload Identity.
resource "google_service_account" "cluster_service_account" {
  account_id   = "svc-guacamole"
  display_name = "svc-guacamole"
  description  = "GCP Service Account bound to K8s svc-guacamole via Workload Identity"
  project      = var.project_id
  depends_on   = [module.project-services]
}

# Custom IAM role with the single permission required to resolve the Load Balancer BackendService ID.
resource "google_project_iam_custom_role" "iap-jwt-verify-role" {
  role_id     = "iap_jwt_verifier"
  title       = "IAP JWT Verifier"
  description = "Retrieve metadata related to IAP JWT Verification"
  permissions = ["compute.backendServices.get"]
  depends_on  = [module.project-services]
}

# Allow the guacamole-client Pod (via svc-guacamole) to resolve its BackendService ID for IAP JWT verification.
resource "google_project_iam_member" "guacamole-iap-verifier" {
  project = var.project_id
  role    = google_project_iam_custom_role.iap-jwt-verify-role.id
  member  = "serviceAccount:${google_service_account.cluster_service_account.email}"
}

# Allow the Cloud SQL Auth Proxy sidecar in the guacamole-client Pod to connect to Cloud SQL via IAM.
resource "google_project_iam_member" "guacamole-cloudsql-client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.cluster_service_account.email}"
}

# Allow the Kubernetes ServiceAccount guacamole/svc-guacamole to impersonate the GCP svc-guacamole account.
# Waiting on google_container_cluster.gke ensures the project's Workload Identity pool exists first.
resource "google_service_account_iam_member" "workload-identity-binding" {
  service_account_id = google_service_account.cluster_service_account.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[guacamole/svc-guacamole]"
  depends_on         = [google_container_cluster.gke]
}

# Dedicated least-privilege GCP Service Account used by GKE Autopilot nodes and the management VM.
resource "google_service_account" "svc-gke-node" {
  account_id  = "svc-gke-node"
  description = "GKE Node Service Account"
  depends_on  = [module.project-services]
}

# Bind standard logging, monitoring, autoscaling, and Artifact Registry reader roles to svc-gke-node.
resource "google_project_iam_member" "svc-gke-node-iam" {
  for_each = toset(local.gke_service_account_roles)

  project = var.project_id
  member  = "serviceAccount:${google_service_account.svc-gke-node.email}"
  role    = each.value
}
