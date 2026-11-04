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

# Render the SQL script that grants Guacamole admin permissions to the deploying Google identity.
# Uses local_sensitive_file (0600) and lowercases the email to match IAP extension normalization.
resource "local_sensitive_file" "insert-admin-user" {
  content = templatefile("${path.module}/templates/insert-admin-user.sql.tmpl", {
    admin_email = lower(local.admin_email)
  })
  filename        = "${path.module}/../insert-admin-user.sql"
  file_permission = "0600"
}

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

# Render non-sensitive client settings consumed by Kustomize to generate the guacamole-client-settings ConfigMap.
# Double-escaping \\. ensures Kustomize's dotenv parser preserves \. in the final ConfigMap regex.
resource "local_file" "client-settings-properties" {
  content = templatefile("${path.module}/templates/client-settings.properties.tmpl", {
    cloud_sql_connection_name = google_sql_database_instance.guacamole-mysql.connection_name
    external_ip               = replace(google_compute_global_address.guacamole-external.address, ".", "\\\\.")
    project_id                = var.project_id
    project_number            = data.google_project.project.number
  })
  filename = "${path.module}/../client/client-settings.properties"
}

# Render sensitive MySQL credentials with 0600 permissions for Kustomize's db-user-pass Secret generator.
resource "local_sensitive_file" "db-secrets-properties" {
  content = templatefile("${path.module}/templates/db-secrets.properties.tmpl", {
    mysql_user     = google_sql_user.guac-db-user.name
    mysql_password = google_sql_user.guac-db-user.password
  })
  filename        = "${path.module}/../client/db-secrets.properties"
  file_permission = "0600"
}
