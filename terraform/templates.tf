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

# Render the GKE ManagedCertificate manifest with the resolved external domain (sslip.io or custom).
resource "local_file" "guacamole-client-managedcert" {
  content = templatefile("${path.module}/templates/guacamole-client.managedcert.yaml.tmpl", {
    remote_url = local.remote_url
  })
  filename = "${path.module}/../client/kubernetes-manifests/guacamole-client.managedcert.yaml"
}

# Render the Kubernetes ServiceAccount manifest annotated with the GCP svc-guacamole email for Workload Identity.
resource "local_file" "guacamole-client-serviceaccount" {
  content = templatefile("${path.module}/templates/guacamole-client.serviceaccount.yaml.tmpl", {
    gcp_service_account_email = google_service_account.cluster_service_account.email
  })
  filename = "${path.module}/../client/kubernetes-manifests/guacamole-client.serviceaccount.yaml"
}

# Render the Kustomize strategic merge patch pointing guacamole-client-iap to Artifact Registry
# so `kubectl apply -k .` works independently without requiring Skaffold.
resource "local_file" "guacamole-client-image-patch" {
  content = templatefile("${path.module}/templates/guacamole-client.image-patch.yaml.tmpl", {
    artifact_registry_repo = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.guac-repo.repository_id}"
  })
  filename = "${path.module}/../client/kubernetes-manifests/guacamole-client.image-patch.yaml"
}

# Render non-sensitive client settings consumed by Kustomize to generate the guacamole-client-settings ConfigMap.
# Double-escaping \\. ensures Kustomize's dotenv parser preserves \. in the final ConfigMap regex.
resource "local_file" "client-settings-properties" {
  content = templatefile("${path.module}/templates/client-settings.properties.tmpl", {
    cloud_sql_connection_name = google_sql_database_instance.guacamole-mysql.connection_name
    db_init_sql_secret_id     = google_secret_manager_secret.db_init_sql.secret_id
    external_ip               = replace(google_compute_global_address.guacamole-external.address, ".", "\\\\.")
    mysql_password_secret_id  = google_secret_manager_secret.db_password.secret_id
    mysql_user                = google_sql_user.guac-db-user.name
    project_id                = var.project_id
    project_number            = data.google_project.project.number
  })
  filename = "${path.module}/../client/client-settings.properties"
}
