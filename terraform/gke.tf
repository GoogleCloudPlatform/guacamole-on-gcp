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

# Provision a private GKE Autopilot cluster using the dedicated least-privilege node service account.
resource "google_container_cluster" "gke" {
  provider        = google
  name            = "guacamole-gke"
  location        = var.region
  networking_mode = "VPC_NATIVE"
  network         = google_compute_network.vpc.id
  subnetwork      = google_compute_subnetwork.subnet.name

  # Run cluster nodes on private IPs while keeping the control plane endpoint reachable for kubectl/skaffold.
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = var.nwr_master_node
  }

  # Enable GKE Autopilot mode and configurable deletion protection.
  enable_autopilot    = true
  deletion_protection = var.deletion_protection

  # Configure Autopilot node auto-provisioning to use the dedicated least-privilege svc-gke-node account.
  cluster_autoscaling {
    auto_provisioning_defaults {
      service_account = google_service_account.svc-gke-node.email
      oauth_scopes = [
        "https://www.googleapis.com/auth/cloud-platform",
      ]
    }
  }

  # Use VPC-native IP allocation for Pod and Service ranges.
  ip_allocation_policy {}

  # Wait for required APIs and node service account IAM bindings before creating the cluster.
  depends_on = [
    module.project-services,
    google_project_iam_member.svc-gke-node-iam,
  ]
}
