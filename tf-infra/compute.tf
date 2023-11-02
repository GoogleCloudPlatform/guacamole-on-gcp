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

resource "google_compute_network" "vpc" {
  provider                = google
  name                    = var.network_name
  auto_create_subnetworks = "false"
}

resource "google_compute_subnetwork" "subnet" {
  name          = "guacamole-host-subnet"
  region        = var.region
  network       = google_compute_network.vpc.name
  ip_cidr_range = "10.10.0.0/24"
}

resource "google_compute_router" "router" {
  name    = "guacamole-router"
  region  = google_compute_subnetwork.subnet.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "guacamole-router-nat"
  router                             = google_compute_router.router.name
  region                             = google_compute_router.router.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

resource "google_compute_global_address" "private_ip_address" {
  provider      = google
  name          = "private-ip-address"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_compute_instance" "db-management" {
  name         = var.db_management_vm
  zone         = var.zone
  machine_type = "e2-micro"

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.name
  }

  shielded_instance_config {
    enable_secure_boot = true
  }

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-10"
    }
  }

  metadata = {
    cloud_sql_ip = google_sql_database_instance.guacamole-mysql.private_ip_address
  }
}

resource "google_compute_firewall" "vpc-firewall" {
  name    = "permit-ssh-via-iap"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["35.235.240.0/20"]
}

resource "google_compute_firewall" "permit-guac-to-vm-traffic" {
  name    = "permit-guacd-to-vm-traffic"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["22", "3389"]
  }

  source_ranges = [google_container_cluster.gke.cluster_ipv4_cidr]
}

resource "google_compute_global_address" "guacamole-external" {
  description  = "External IP Address Reservation for the Load Balancer"
  name         = "guacamole-external"
  address_type = "EXTERNAL"
}