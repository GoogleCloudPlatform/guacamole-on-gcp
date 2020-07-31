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

variable "cluster_name" {
  description = "GKE Cluster to host Guacamole"
  default     = "guacamole-gke"
}