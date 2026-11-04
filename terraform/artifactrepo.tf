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
