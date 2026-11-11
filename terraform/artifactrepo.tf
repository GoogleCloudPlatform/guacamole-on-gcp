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

# Docker repository in Artifact Registry used to store the custom guacamole-client-iap image.
resource "google_artifact_registry_repository" "guac-repo" {
  location      = var.region
  repository_id = "guac-repo"
  description   = "Docker Repository For IAP Enabled Guacamole"
  format        = "DOCKER"
  depends_on    = [module.project-services]
}

# Allow the least-privilege GKE node service account to pull images from guac-repo.
resource "google_artifact_registry_repository_iam_member" "artifactregistry-reader-gke-node" {
  project    = google_artifact_registry_repository.guac-repo.project
  location   = google_artifact_registry_repository.guac-repo.location
  repository = google_artifact_registry_repository.guac-repo.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.svc-gke-node.email}"
}

# Allow the Compute Engine default service account (used by Cloud Build) to push built images to guac-repo.
resource "google_artifact_registry_repository_iam_member" "artifactregistry-writer-compute-default" {
  project    = google_artifact_registry_repository.guac-repo.project
  location   = google_artifact_registry_repository.guac-repo.location
  repository = google_artifact_registry_repository.guac-repo.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${data.google_compute_default_service_account.default.email}"
}

# Cloud Build staging and build-logs bucket expected by `skaffold run -p cloudbuild` (<project_id>_cloudbuild).
# Pre-creating this bucket avoids a Skaffold bug where `createBucketIfNotExists` fails on wrapped 404 errors,
# and `force_destroy = true` ensures `terraform destroy` removes staged build logs cleanly.
resource "google_storage_bucket" "cloudbuild-bucket" {
  name                        = "${var.project_id}_cloudbuild"
  project                     = var.project_id
  location                    = var.region
  force_destroy               = true
  uniform_bucket_level_access = true
  depends_on                  = [module.project-services]
}

# Allow the Compute Engine default service account (used by Cloud Build) to read staged source archives
# and write build logs in the <project_id>_cloudbuild bucket.
resource "google_storage_bucket_iam_member" "cloudbuild-bucket-admin-compute-default" {
  bucket = google_storage_bucket.cloudbuild-bucket.name
  role   = "roles/storage.admin"
  member = "serviceAccount:${data.google_compute_default_service_account.default.email}"
}

# Allow the Compute Engine default service account (used by Cloud Build) to write build logs to Cloud Logging.
resource "google_project_iam_member" "cloudbuild-logging-compute-default" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${data.google_compute_default_service_account.default.email}"
}
