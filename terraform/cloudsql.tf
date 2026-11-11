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

# Establish VPC peering with Service Networking so Cloud SQL can be reached over Private IP.
# deletion_policy = "ABANDON" avoids peering teardown hangs when Cloud SQL IP release lags during destroy.
resource "google_service_networking_connection" "private_vpc_connection" {
  provider                = google
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_address.name]
  deletion_policy         = "ABANDON"
  depends_on              = [module.project-services]
}

# Provision the private Cloud SQL for MySQL 8.4 (Enterprise edition) instance used to store Guacamole connections and permissions.
resource "google_sql_database_instance" "guacamole-mysql" {
  /*
    Random instance name needed because:
    "You cannot reuse an instance name for up to a week after you have deleted an instance."
    See https://cloud.google.com/sql/docs/mysql/delete-instance for details.
  */
  name                = "${var.db_name}-${random_id.suffix.hex}"
  database_version    = "MYSQL_8_4"
  region              = var.region
  deletion_protection = var.deletion_protection

  depends_on = [google_service_networking_connection.private_vpc_connection]

  # Configure Enterprise edition and instance tier, and disable public IP so all connections traverse the private VPC via Cloud SQL Auth Proxy.
  settings {
    edition = "ENTERPRISE"
    tier    = "db-f1-micro"
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc.id
    }
  }
}

# Create the guacamole database schema container with modern 4-byte UTF-8 encoding.
resource "google_sql_database" "guacamole" {
  name      = "guacamole"
  instance  = google_sql_database_instance.guacamole-mysql.name
  charset   = "utf8mb4"
  collation = "utf8mb4_general_ci"
}

# Application MySQL user referenced by the guacamole-client JDBC extension and init-db container.
resource "google_sql_user" "guac-db-user" {
  name     = var.db_username
  instance = google_sql_database_instance.guacamole-mysql.name
  host     = "%"
  password = random_password.db_password.result
}
