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

provider "google" {
  region  = var.region
  zone    = var.zone
  project = var.project_id
}

#provider "google-beta" {
#  region  = var.region
#  zone    = var.zone
#  project = var.project_id
#}

provider "null" {
}

provider "random" {
}

provider "external" {
}

provider "local" {
}

locals {
  gke_service_account_roles = [
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer"
  ]

  remote_url = var.external_url == "sslip.io" ? lookup(data.external.wildcard-dns-url.result, "certdomain", "unknown") : var.external_url
}

resource "random_id" "suffix" {
  byte_length = 5
}

resource "random_password" "db_password" {
  length  = 16
  special = false
}

resource "random_password" "db_root_password" {
  length  = 16
  special = false
}

resource "random_password" "keystore_password" {
  length  = 16
  special = false
}

#resource "google_project_service" "project_services" {
#  for_each = toset(var.required_apis)
#  project  = var.project_id
#  service  = each.value
#}

module "project-services" {
  source = "terraform-google-modules/project-factory/google//modules/project_services"

  project_id = var.project_id

  activate_apis = toset(var.required_apis)
}

resource "google_iap_brand" "project_brand" {
  depends_on        = [module.project-services]
  support_email     = data.google_client_openid_userinfo.me.email
  application_title = "Guacamole on GKE Tutorial"
  project           = var.project_id
}

resource "google_iap_client" "project_client" {
  display_name = "Guacamole IAP Client"
  brand        = google_iap_brand.project_brand.name
}

#resource "google_container_registry" "registry" {
#  depends_on = [module.project-services]
#}

#resource "google_storage_bucket_iam_member" "gke-read-cloudrepo" {
#  bucket = google_container_registry.registry.id
#  role   = "roles/storage.objectViewer"
#  member = "serviceAccount:${google_service_account.svc-gke-node.email}"
#}

data "google_compute_default_service_account" "default" {}

data "google_client_openid_userinfo" "me" {}

data "google_client_config" "provider" {}

data "google_project" "project" {}

data "external" "wildcard-dns-url" {
  program = ["./bin/sslip-io-url.sh"]

  query = {
    externalip = google_compute_global_address.guacamole-external.address
  }
}




