variable "project_id" {
  type        = string
  description = "The project to run tests against"
}

variable "region" {
  description = "The Google Cloud region to deploy Guacamole into"
  default     = "us-central1"
}

variable "zone" {
  description = "For zonal Guacamole resources, deploy into this zone"
  default     = "us-central1-c"
}

variable "network_name" {
  description = "VPC to use for Guacamole resources"
  default     = "guacamole-vpc"
  type        = string
}

variable "db_name" {
  description = "CloudSQL Instance Name"
  default     = "guacamole-mysql"
}

variable "db_username" {
  description = "Guacamole Database User"
  default     = "guac-db-user"
}

variable "external_url" {
  description = "URL used to access Guacamole - defaults to sslip.io, a wildcard DNS service. Change this if you have wish to use your own domain and will create the A record manually."
  default     = "sslip.io"
}

variable "db_management_vm" {
  description = "Google Compute Engine VM used to manage the Guacamole Database."
  default     = "db-mgmt-vm"
}

variable "nwr_master_node" {
  description = "GKE Private Cluster Master Node Network Range"
  default     = "172.16.0.32/28"
}

variable "required_apis" {
  description = "Google Cloud APIs required by this tutorial."
  default = ["compute.googleapis.com",
    "container.googleapis.com",
    "containerregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "servicenetworking.googleapis.com",
    "iap.googleapis.com",
    "sqladmin.googleapis.com",
  "stackdriver.googleapis.com"]
}