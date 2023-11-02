
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

resource "google_project_iam_member" "iap-tcp-user" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "user:${data.google_client_openid_userinfo.me.email}"
}

resource "google_project_iam_member" "iap-web-user" {
  project = var.project_id
  role    = "roles/iap.httpsResourceAccessor"
  member  = "user:${data.google_client_openid_userinfo.me.email}"
}

#resource "google_service_account" "cluster_service_account" {
#  account_id   = "svc-guacamole"
#  display_name = "svc-guacamole"
#  description  = "GCP SA bound to K8S SA"
#  project      = var.project_id
#}

#resource "google_service_account_iam_member" "main" {
#  depends_on         = [google_container_cluster.gke]
#  service_account_id = google_service_account.cluster_service_account.name
#  role               = "roles/iam.workloadIdentityUser"
#  member             = "serviceAccount:${var.project_id}.svc.id.goog[guacamole/svc-guacamole]"
#}

resource "google_project_iam_custom_role" "iap-jwt-verify-role" {
  role_id     = "iap_jwt_verifier"
  title       = "IAP JWT Verifier"
  description = "Retrieve metadata related to IAP JWT Verification"
  permissions = ["compute.backendServices.get"]
}

resource "google_service_account" "svc-gke-node" {
  account_id  = "svc-gke-node"
  description = "GKE Node Service Account"
}

resource "google_project_iam_member" "svc-gke-node-iam" {
  for_each = toset(local.gke_service_account_roles)

  project = var.project_id
  member  = "serviceAccount:${google_service_account.svc-gke-node.email}"
  role    = each.value
}
