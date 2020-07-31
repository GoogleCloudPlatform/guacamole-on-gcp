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

provider "kubernetes" {
  host                   = "https://${data.google_container_cluster.gke.endpoint}"
  token                  = data.google_client_config.provider.access_token
  cluster_ca_certificate = base64decode(data.google_container_cluster.gke.master_auth.0.cluster_ca_certificate)
}

data "google_client_config" "provider" {}

data "google_container_cluster" "gke" {
  name     = var.cluster_name
  location = var.region
}

resource "kubernetes_namespace" "guacamole-ns" {
  metadata {
    name = "guacamole"
  }
}

module "guacamole-workload-identity" {
  source                          = "terraform-google-modules/kubernetes-engine/google//modules/workload-identity"
  name                            = "svc-guacamole"
  namespace                       = kubernetes_namespace.guacamole-ns.metadata[0].name
  project_id                      = var.project_id
  use_existing_k8s_sa             = false
  automount_service_account_token = true
  roles                           = ["projects/${var.project_id}/roles/iap_jwt_verifier"]
}