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

# Dedicated least-privilege GCP Service Account for Google Cloud Deploy render and deploy operations.
resource "google_service_account" "clouddeploy_execution" {
  account_id   = "svc-clouddeploy"
  display_name = "svc-clouddeploy"
  description  = "Execution Service Account for Google Cloud Deploy render and deploy jobs"
  project      = var.project_id
  depends_on   = [module.project-services]
}

# Grant Cloud Deploy jobRunner, GKE container.developer, and logging roles to svc-clouddeploy.
resource "google_project_iam_member" "clouddeploy_execution_roles" {
  for_each = toset([
    "roles/clouddeploy.jobRunner",
    "roles/container.developer",
    "roles/logging.logWriter",
    "roles/artifactregistry.reader",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.clouddeploy_execution.email}"
}

# Allow the deploying operator to act as svc-clouddeploy when creating releases and rollouts.
resource "google_service_account_iam_member" "clouddeploy_user_admin" {
  service_account_id = google_service_account.clouddeploy_execution.name
  role               = "roles/iam.serviceAccountUser"
  member             = local.admin_iam_member
}

# Allow the Cloud Deploy Service Agent to act as svc-clouddeploy during automated rollout phases.
resource "google_service_account_iam_member" "clouddeploy_service_agent_user" {
  service_account_id = google_service_account.clouddeploy_execution.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:service-${data.google_project.project.number}@gcp-sa-clouddeploy.iam.gserviceaccount.com"
  depends_on         = [google_clouddeploy_delivery_pipeline.guacamole_pipeline]
}

# Dedicated Cloud Storage bucket for Google Cloud Deploy source archives and rendered manifests.
# Using force_destroy = true ensures `terraform destroy` cleans up all release artifacts cleanly.
resource "google_storage_bucket" "clouddeploy_bucket" {
  name                        = "${var.project_id}_clouddeploy"
  project                     = var.project_id
  location                    = var.region
  force_destroy               = true
  uniform_bucket_level_access = true
  depends_on                  = [module.project-services]
}

# Grant svc-clouddeploy storage access to read staged sources and write rendered artifacts.
resource "google_storage_bucket_iam_member" "clouddeploy_bucket_admin" {
  bucket = google_storage_bucket.clouddeploy_bucket.name
  role   = "roles/storage.admin"
  member = "serviceAccount:${google_service_account.clouddeploy_execution.email}"
}

# Google Cloud Deploy Target pointing to the private GKE Autopilot cluster.
resource "google_clouddeploy_target" "guacamole_gke" {
  project     = var.project_id
  location    = var.region
  name        = "guacamole-gke"
  description = "GKE Autopilot cluster target for Apache Guacamole"

  gke {
    cluster = google_container_cluster.gke.id
  }

  execution_configs {
    usages           = ["RENDER", "DEPLOY"]
    service_account  = google_service_account.clouddeploy_execution.email
    artifact_storage = "gs://${google_storage_bucket.clouddeploy_bucket.name}/artifacts"
  }

  depends_on = [
    module.project-services,
    google_project_iam_member.clouddeploy_execution_roles,
    google_storage_bucket_iam_member.clouddeploy_bucket_admin,
  ]
}

# Google Cloud Deploy Delivery Pipeline for deploying Guacamole to the guacamole-gke target.
resource "google_clouddeploy_delivery_pipeline" "guacamole_pipeline" {
  project     = var.project_id
  location    = var.region
  name        = "guacamole-pipeline"
  description = "Continuous delivery pipeline for Apache Guacamole on GKE Autopilot"

  serial_pipeline {
    stages {
      target_id = google_clouddeploy_target.guacamole_gke.name
    }
  }

  depends_on = [module.project-services]
}
