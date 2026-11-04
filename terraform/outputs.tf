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

# Target Google Cloud project ID.
output "project_id" {
  value       = var.project_id
  description = "The Google Cloud project ID where Guacamole is deployed"
}

# Google account email granted IAP access and initial Guacamole administrator permissions.
output "admin_email" {
  value       = local.admin_email
  description = "Google account email granted IAP access and initial Guacamole administrator permissions"
}

# Private VPC IPv4 address of the Cloud SQL for MySQL instance.
output "db_address" {
  value       = google_sql_database_instance.guacamole-mysql.private_ip_address
  description = "Private IP Address for MySQL Database"
}

# Generated Cloud SQL instance name (including the random hex suffix).
output "cloud_sql_instance_name" {
  value       = google_sql_database_instance.guacamole-mysql.name
  description = "Name of the Cloud SQL MySQL instance"
}

# Full Cloud SQL connection name (project:region:instance) used by the Cloud SQL Auth Proxy sidecar.
output "cloud_sql_connection_name" {
  value       = google_sql_database_instance.guacamole-mysql.connection_name
  description = "Connection name (project:region:instance) for the Cloud SQL Auth Proxy"
}

# Application MySQL username.
output "db_username" {
  value       = google_sql_user.guac-db-user.name
  description = "Guacamole DB User Name"
}

# External hostname (sslip.io or custom domain) used to access Guacamole over HTTPS.
output "external_url" {
  value       = local.remote_url
  description = "URL used to access Guacamole"
}

# Google Cloud zone where zonal resources are deployed.
output "cloud_zone" {
  value       = var.zone
  description = "Google Cloud Zone that resources have been deployed into"
}

# Google Cloud region where regional resources (GKE, Cloud SQL, Artifact Registry) are deployed.
output "cloud_region" {
  value       = var.region
  description = "Google Cloud Region that resources have been deployed into"
}

# Name of the GKE Autopilot cluster.
output "gke_cluster_name" {
  value       = google_container_cluster.gke.name
  description = "Name of the GKE Cluster"
}

# Name of the VPC subnetwork where VMs and GKE nodes run.
output "subnet" {
  value       = google_compute_subnetwork.subnet.name
  description = "Network subnet to attach test VMs to"
}

# Full Artifact Registry repository path used when building and deploying guacamole-client-iap.
output "artifact_registry_repo" {
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.guac-repo.repository_id}"
  description = "Artifact Registry Docker repository URI"
}

# Name of the Google Cloud Deploy delivery pipeline.
output "clouddeploy_pipeline" {
  value       = google_clouddeploy_delivery_pipeline.guacamole_pipeline.name
  description = "Google Cloud Deploy delivery pipeline name"
}
