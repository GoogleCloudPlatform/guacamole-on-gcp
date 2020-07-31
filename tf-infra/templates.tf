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

resource "local_file" "insert-admin-user" {
  content = templatefile("${path.module}/templates/insert-admin-user.sql.tmpl", {
    admin_email = data.google_client_openid_userinfo.me.email
  })
  filename = "${path.module}/../insert-admin-user.sql"
}

resource "local_file" "iap-secrets" {
  content = templatefile("${path.module}/templates/iap-secrets.properties.tmpl", {
    client_id     = google_iap_client.project_client.client_id
    client_secret = google_iap_client.project_client.secret
  })
  filename = "${path.module}/../client/iap-secrets.properties"
}

resource "local_file" "guacamole-properties" {
  content = templatefile("${path.module}/templates/guacamole.properties.tmpl", {
    project_id     = var.project_id
    project_number = data.google_project.project.number
  })
  filename = "${path.module}/../client/guacamole.properties"
}

resource "local_file" "guacamole-client-managedcert" {
  content = templatefile("${path.module}/templates/guacamole-client.managedcert.yaml.tmpl", {
    remote_url = local.remote_url
  })
  filename = "${path.module}/../client/kubernetes-manifests/guacamole-client.managedcert.yaml"
}

resource "local_file" "client-settings-properties" {
  content = templatefile("${path.module}/templates/client-settings.properties.tmpl", {
    db_address           = google_sql_database_instance.guacamole-mysql.private_ip_address
    truststore_password  = random_password.keystore_password.result
    clientstore_password = random_password.keystore_password.result
  })

  filename = "${path.module}/../client/client-settings.properties"
}

resource "local_file" "tomcat-server-xml" {
  content = templatefile("${path.module}/templates/server.xml.tmpl", {
    external_ip = replace(google_compute_global_address.guacamole-external.address, ".", "\\.")
  })

  filename = "${path.module}/../client/tomcat/conf/server.xml"
}

resource "local_file" "db-secrets-properties" {
  content = templatefile("${path.module}/templates/db-secrets.properties.tmpl", {
    mysql_user     = google_sql_user.guac-db-user.name
    mysql_password = google_sql_user.guac-db-user.password
  })

  filename = "${path.module}/../client/db-secrets.properties"
}