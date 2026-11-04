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

# Custom-mode VPC network to isolate GKE Autopilot, Cloud SQL, and target VMs.
resource "google_compute_network" "vpc" {
  provider                = google
  name                    = var.network_name
  auto_create_subnetworks = false
  depends_on              = [module.project-services]
}

# Primary regional subnet for GKE Autopilot nodes and Compute Engine VMs.
resource "google_compute_subnetwork" "subnet" {
  name          = "guacamole-host-subnet"
  region        = var.region
  network       = google_compute_network.vpc.name
  ip_cidr_range = "10.10.0.0/24"
}

# Cloud Router attached to the VPC to support Cloud NAT egress for private nodes and VMs.
resource "google_compute_router" "router" {
  name    = "guacamole-router"
  region  = google_compute_subnetwork.subnet.region
  network = google_compute_network.vpc.id
}

# Cloud NAT gateway allowing private GKE nodes to reach outbound packages and Google APIs.
resource "google_compute_router_nat" "nat" {
  name                               = "guacamole-router-nat"
  router                             = google_compute_router.router.name
  region                             = google_compute_router.router.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# Internal /16 IP range reserved for VPC peering with Service Networking (Cloud SQL Private IP).
resource "google_compute_global_address" "private_ip_address" {
  provider      = google
  name          = "private-ip-address"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

# Allow inbound SSH from Google's Identity-Aware Proxy TCP forwarding range (35.235.240.0/20).
resource "google_compute_firewall" "vpc-firewall" {
  name    = "permit-ssh-via-iap"
  network = google_compute_network.vpc.name

  # Permit TCP port 22 so operators can tunnel SSH sessions through IAP without public VM IPs.
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["35.235.240.0/20"]
}

# Allow guacd pods in GKE to initiate SSH (22) and RDP (3389) connections to target VMs in the VPC.
resource "google_compute_firewall" "permit-guac-to-vm-traffic" {
  name    = "permit-guacd-to-vm-traffic"
  network = google_compute_network.vpc.name

  # Permit SSH (22) and RDP (3389) from guacd to internal Compute Engine instances.
  allow {
    protocol = "tcp"
    ports    = ["22", "3389"]
  }

  # Include both the GKE pod CIDR and node subnet CIDR to cover intra-VPC pod-to-VM and SNAT traffic.
  source_ranges = [
    google_container_cluster.gke.cluster_ipv4_cidr,
    google_compute_subnetwork.subnet.ip_cidr_range,
  ]
}

# Global static external IPv4 address bound to the GKE Ingress Load Balancer ("guacamole-external").
resource "google_compute_global_address" "guacamole-external" {
  description  = "External IP Address Reservation for the Load Balancer"
  name         = "guacamole-external"
  address_type = "EXTERNAL"
  depends_on   = [module.project-services]
}
