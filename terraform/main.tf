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

# Configure the Google Cloud provider with the target project, region, zone, and OpenID email scope.
provider "google" {
  region  = var.region
  zone    = var.zone
  project = var.project_id
  scopes = [
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/userinfo.email",
  ]
}

# Define least-privilege node IAM roles, resolve the admin identity, and compute the external sslip.io domain.
locals {
  # Standard least-privilege IAM roles required for GKE Autopilot nodes to write
  # logs/metrics and pull container images from Artifact Registry.
  gke_service_account_roles = [
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/autoscaling.metricsWriter",
    "roles/artifactregistry.reader",
  ]

  # Read the active gcloud configuration name from ~/.config/gcloud/active_config (defaults to "default").
  gcloud_config_dir    = pathexpand("~/.config/gcloud")
  gcloud_active_config = try(trimspace(file("${local.gcloud_config_dir}/active_config")), "default")

  # Extract the active account email from ~/.config/gcloud/configurations/config_<name> if present.
  gcloud_home_email = try(
    trimspace(regex("(?m)^\\s*account\\s*=\\s*(\\S+)", file("${local.gcloud_config_dir}/configurations/config_${local.gcloud_active_config}"))[0]),
    ""
  )

  # Resolve the admin email in priority order:
  # 1. Explicit var.admin_email
  # 2. OpenID userinfo from Application Default Credentials (data.google_client_openid_userinfo.me.email)
  # 3. Active gcloud CLI config in ~/.config/gcloud
  # 4. Active gcloud / Cloud Shell config resolved via data.external.gcloud_account
  admin_email = try(
    coalesce(
      trimspace(var.admin_email),
      trimspace(data.google_client_openid_userinfo.me.email),
      local.gcloud_home_email,
      trimspace(data.external.gcloud_account.result.email),
    ),
    ""
  )

  # Format the IAM principal prefix appropriately for human Google accounts vs GCP service accounts.
  admin_iam_member = endswith(local.admin_email, ".gserviceaccount.com") ? "serviceAccount:${local.admin_email}" : "user:${local.admin_email}"

  # Replace dots with dashes in the reserved global IP when using the default sslip.io wildcard DNS.
  remote_url = var.external_url == "sslip.io" ? "${replace(google_compute_global_address.guacamole-external.address, ".", "-")}.sslip.io" : var.external_url
}

# Random hex suffix appended to the Cloud SQL instance name because Cloud SQL names
# cannot be reused immediately after deletion.
resource "random_id" "suffix" {
  byte_length = 5
}

# Random password generated for the application MySQL user (guac-db-user).
resource "random_password" "db_password" {
  length  = 16
  special = false
}

# Enable all required Google Cloud APIs before creating project resources.
module "project-services" {
  source  = "terraform-google-modules/project-factory/google//modules/project_services"
  version = "~> 15.0"

  project_id                  = var.project_id
  activate_apis               = toset(var.required_apis)
  disable_services_on_destroy = false
}

# Retrieve the default Compute Engine service account (used by Cloud Build to push images).
data "google_compute_default_service_account" "default" {
  depends_on = [module.project-services]
}

# Retrieve the authenticated operator's email address to bootstrap the initial Guacamole admin user.
data "google_client_openid_userinfo" "me" {}

# Fallback lookup for the active gcloud / Cloud Shell account when ADC lacks the OpenID userinfo.email scope.
data "external" "gcloud_account" {
  program = [
    "sh",
    "-c",
    <<-EOT
      read_cfg() {
        dir="$1"
        [ -n "$dir" ] && [ -d "$dir" ] || return 0
        active="$(cat "$dir/active_config" 2>/dev/null || echo default)"
        sed -n 's/^[[:space:]]*account[[:space:]]*=[[:space:]]*\([^[:space:]]*\).*/\1/p' "$dir/configurations/config_$active" 2>/dev/null | head -n 1
      }
      email="$(read_cfg "$CLOUDSDK_CONFIG")"
      if [ -z "$email" ]; then
        email="$(read_cfg "$HOME/.config/gcloud")"
      fi
      if [ -z "$email" ] && [ -n "$GCE_METADATA_HOST" ]; then
        meta_email="$(curl -sf -m 2 -H "Metadata-Flavor: Google" "http://$GCE_METADATA_HOST/computeMetadata/v1/instance/service-accounts/default/email" 2>/dev/null || true)"
        case "$meta_email" in
          *.gserviceaccount.com) ;;
          *) email="$meta_email" ;;
        esac
      fi
      email="$(printf '%s' "$email" | tr -d '\r\n"\\')"
      printf '{"email":"%s"}\n' "$email"
    EOT
  ]
}

# Retrieve project metadata including the numeric project number required for IAP JWT audience validation.
data "google_project" "project" {
  depends_on = [module.project-services]
}
