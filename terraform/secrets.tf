#
# Copyright 2026 Google LLC
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

# Secret Manager secret storing the Cloud SQL MySQL password for guac-db-user.
resource "google_secret_manager_secret" "db_password" {
  project   = var.project_id
  secret_id = "guacamole-db-password"

  replication {
    auto {}
  }

  depends_on = [module.project-services]
}

# Store the generated Cloud SQL user password as the latest version of guacamole-db-password.
resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = google_sql_user.guac-db-user.password
}

# Secret Manager secret storing the idempotent SQL script that grants Guacamole admin permissions.
resource "google_secret_manager_secret" "db_init_sql" {
  project   = var.project_id
  secret_id = "guacamole-db-init-sql"

  replication {
    auto {}
  }

  depends_on = [module.project-services]
}

# Store the rendered insert-admin-user.sql content as the latest version of guacamole-db-init-sql.
resource "google_secret_manager_secret_version" "db_init_sql" {
  secret = google_secret_manager_secret.db_init_sql.id
  secret_data = templatefile("${path.module}/templates/insert-admin-user.sql.tmpl", {
    admin_email = lower(local.admin_email)
  })
}

# Grant the Workload Identity service account (svc-guacamole) read access to the MySQL password secret.
resource "google_secret_manager_secret_iam_member" "db_password_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.db_password.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cluster_service_account.email}"
}

# Grant the Workload Identity service account (svc-guacamole) read access to the admin bootstrap SQL secret.
resource "google_secret_manager_secret_iam_member" "db_init_sql_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.db_init_sql.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cluster_service_account.email}"
}
