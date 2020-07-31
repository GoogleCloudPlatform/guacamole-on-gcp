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

output "project_id" {
  value       = var.project_id
  description = "The project to run tests against"
}

output "db_address" {
  value       = google_sql_database_instance.guacamole-mysql.private_ip_address
  description = "Private IP Address for MySQL Database"
}

output "db_username" {
  value       = google_sql_user.guac-db-user.name
  description = "Guacamole DB User Name"
}

output "db_password" {
  value       = google_sql_user.guac-db-user.password
  description = "Guacamole DB User Password"
  sensitive   = true
}

output "db_root_password" {
  value       = google_sql_user.guac-db-root.password
  description = "Guacamole DB Root Password"
  sensitive   = true
}

output "external_url" {
  value       = local.remote_url
  description = "URL used to access Guacamole"
}

output "oauth_authorized_redirect_url" {
  value       = "https://iap.googleapis.com/v1/lauth/cliendIds/${google_iap_client.project_client.client_id}:handleRedirect"
  description = "Universal Redirect URL to be added to OAuth Credentials via Google Cloud Console."
}

output "db_mgmt_vm" {
  value       = google_compute_instance.db-management.name
  description = "Database Management VM Name"
}

output "cloud_zone" {
  value       = var.zone
  description = "Google Cloud Zone that tutorial resources have been deployed into"
}

output "cloud_region" {
  value       = var.region
  description = "Google Cloud Region that tutorial resources have been deployed into"
}

output "gke_cluster_name" {
  value       = google_container_cluster.gke.name
  description = "Name of the GKE Cluster"
}

output "subnet" {
  value       = google_compute_subnetwork.subnet.name
  description = "Network subnet to attach test VMs to"
}