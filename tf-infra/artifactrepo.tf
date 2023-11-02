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

resource "google_artifact_registry_repository" "guac-repo" {
  location      = var.region
  repository_id = "guac-repo"
  description   = "Docker Repository For IAP Enabled Guacamole"
  format        = "DOCKER"
}

resource "google_artifact_registry_repository_iam_member" "artifactregistry-iam" {
  project = google_artifact_registry_repository.guac-repo.project
  location = google_artifact_registry_repository.guac-repo.location
  repository = google_artifact_registry_repository.guac-repo.name
  role = "roles/artifactregistry.reader"
  member = "serviceAccount:${data.google_compute_default_service_account.default.email}"
}
