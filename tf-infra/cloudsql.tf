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

resource "google_service_networking_connection" "private_vpc_connection" {
  provider                = google
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_address.name]
}

resource "google_sql_database_instance" "guacamole-mysql" {
  /*
    Random instance name needed because:
    "You cannot reuse an instance name for up to a week after you have deleted an instance."
    See https://cloud.google.com/sql/docs/mysql/delete-instance for details.
    */
  name             = "${var.db_name}-${random_id.suffix.hex}"
  database_version = "MYSQL_5_7"
  region           = var.region

  depends_on = [google_service_networking_connection.private_vpc_connection]

  settings {
    tier = "db-f1-micro"
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc.id
    }
  }
}

resource "google_sql_user" "guac-db-user" {
  name     = var.db_username
  instance = google_sql_database_instance.guacamole-mysql.name
  host     = "%"
  password = random_password.db_password.result
}

resource "google_sql_user" "guac-db-root" {
  name     = "root"
  instance = google_sql_database_instance.guacamole-mysql.name
  host     = "%"
  password = random_password.db_root_password.result
}

resource "google_sql_ssl_cert" "db-client-cert" {
  common_name = "guac-db-client-cert-${random_id.suffix.hex}"
  instance    = google_sql_database_instance.guacamole-mysql.name
}

data "external" "db-client-cert-keystore" {
  program     = ["bash", "${path.module}/tf-infra/bin/generate-keystore.sh"]
  working_dir = "../"

  query = {
    keystore_password = "${random_password.keystore_password.result}"
    common_name       = "${google_sql_ssl_cert.db-client-cert.common_name}"
    server_ca_cert    = "${google_sql_ssl_cert.db-client-cert.server_ca_cert}"
    cert              = "${google_sql_ssl_cert.db-client-cert.cert}"
    private_key       = "${google_sql_ssl_cert.db-client-cert.private_key}"
  }
}