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

resource "google_container_cluster" "gke" {
  provider           = google
  name               = "guacamole-gke"
  location           = var.region
  networking_mode    = "VPC_NATIVE"
  network            = google_compute_network.vpc.id
  subnetwork         = google_compute_subnetwork.subnet.name

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = var.nwr_master_node
  }

  enable_autopilot = true

  #Updated to TF Provider 5.6, no longer need to explicityly define the below block, as it's the default now
  #When using TF provider <4.80, need to explicitly define CLOUD_DNS as cluster_dns per b/295958728
  #dns_config {
  #  cluster_dns        = "CLOUD_DNS"
  #  cluster_dns_domain = "cluster.local"
  #  cluster_dns_scope  = "CLUSTER_SCOPE"
  #}

  ip_allocation_policy {}
}


