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

# Configure the Google Cloud provider with the target project, region, and zone.
provider "google" {
  region  = var.region
  zone    = var.zone
  project = var.project_id
}

# Define least-privilege node IAM roles and compute the external sslip.io domain in HCL.
locals {
  # Standard least-privilege IAM roles required for GKE Autopilot nodes to write
  # logs/metrics and pull container images from Artifact Registry.
  gke_service_account_roles = [
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/autoscaling.metricsWriter",
    "roles/artifactregistry.reader",
  ]

  # Replace dots with dashes in the reserved global IP when using the default sslip.io wildcard DNS.
  remote_url = var.external_url == "sslip.io" ? "${replace(google_compute_global_address.guacamole-external.address, ".", "-")}.sslip.io" : var.external_url
}

# Random hex suffix appended to the Cloud SQL instance name because Cloud SQL names
# cannot be reused immediately after deletion.
resource "random_id" "suffix" {
  byte_length = 5
}

# Random password generated for the application MySQL user (guac-db-user).
resource "random_password" "db_password" {
  length  = 16
  special = false
}

# Random password generated for the Cloud SQL root user.
resource "random_password" "db_root_password" {
  length  = 16
  special = false
}

# Enable all required Google Cloud APIs before creating project resources.
module "project-services" {
  source  = "terraform-google-modules/project-factory/google//modules/project_services"
  version = "~> 15.0"

  project_id                  = var.project_id
  activate_apis               = toset(var.required_apis)
  disable_services_on_destroy = false
}

# Retrieve the default Compute Engine service account (used by Cloud Build to push images).
data "google_compute_default_service_account" "default" {
  depends_on = [module.project-services]
}

# Retrieve the authenticated operator's email address to bootstrap the initial Guacamole admin user.
data "google_client_openid_userinfo" "me" {}

# Retrieve project metadata including the numeric project number required for IAP JWT audience validation.
data "google_project" "project" {
  depends_on = [module.project-services]
}
