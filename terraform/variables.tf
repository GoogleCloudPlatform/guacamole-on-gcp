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

# Target Google Cloud project ID where Guacamole infrastructure will be deployed.
variable "project_id" {
  type        = string
  description = "The Google Cloud project ID to deploy Guacamole into"
}

# Optional explicit Google account email for IAP access and Guacamole admin bootstrap.
# Defaults to the email from Application Default Credentials (google_client_openid_userinfo).
variable "admin_email" {
  type        = string
  description = "Google account email granted IAP access and Guacamole admin permissions (defaults to ADC user email)"
  default     = ""
}

# Regional deployment location for GKE Autopilot, Cloud SQL, and Artifact Registry.
variable "region" {
  type        = string
  description = "The Google Cloud region to deploy Guacamole into"
  default     = "us-central1"
}

# Zonal location for the Compute Engine database management and test VM.
variable "zone" {
  type        = string
  description = "For zonal Guacamole resources, deploy into this zone"
  default     = "us-central1-c"
}

# Custom VPC network name used to isolate private GKE nodes and Cloud SQL.
variable "network_name" {
  type        = string
  description = "VPC to use for Guacamole resources"
  default     = "guacamole-vpc"
}

# Base name for the Cloud SQL for MySQL instance (suffixed with a random hex ID).
variable "db_name" {
  type        = string
  description = "CloudSQL Instance Name"
  default     = "guacamole-mysql"
}

# Application database user account used by the Guacamole JDBC extension.
variable "db_username" {
  type        = string
  description = "Guacamole Database User"
  default     = "guac-db-user"
}

# External domain name for the Managed Certificate; defaults to sslip.io wildcard DNS.
variable "external_url" {
  type        = string
  description = "URL used to access Guacamole - defaults to sslip.io, a wildcard DNS service. Change this if you wish to use your own domain and will create the A record manually."
  default     = "sslip.io"
}

# Internal RFC 1918 /28 CIDR block reserved for the GKE private cluster control plane.
variable "nwr_master_node" {
  type        = string
  description = "GKE Private Cluster Master Node Network Range"
  default     = "172.16.0.32/28"
}

# Controls whether GKE and Cloud SQL block terraform destroy until explicitly disabled.
variable "deletion_protection" {
  type        = bool
  description = "Whether to enable Terraform deletion protection on the GKE cluster and Cloud SQL instance."
  default     = false
}

# Google Cloud APIs that must be enabled before provisioning project infrastructure.
variable "required_apis" {
  type        = list(string)
  description = "Google Cloud APIs required by this deployment."
  default = [
    # Required for storing custom container images, Cloud Build, and Cloud Deploy.
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "clouddeploy.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    # Required for VPC networking, GKE Autopilot, and Workload Identity IAM bindings.
    "compute.googleapis.com",
    "container.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    # Required for Identity-Aware Proxy, Secret Manager, observability, and private Cloud SQL peering.
    "iap.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "serviceusage.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com",
  ]
}
