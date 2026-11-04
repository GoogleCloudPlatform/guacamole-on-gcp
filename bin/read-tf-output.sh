#!/bin/bash
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

# Helper script meant to be sourced (`source bin/read-tf-output.sh`) to export
# Terraform outputs as environment variables for subsequent gcloud / skaffold commands.
# Do NOT enable `set -e` or call `exit` here so sourcing never terminates the user's shell.

# Export Cloud SQL private IP, instance name, and Cloud SQL Auth Proxy connection name.
export CLOUD_SQL_IP="$(terraform -chdir=terraform output -raw db_address)"
export CLOUD_SQL_INSTANCE="$(terraform -chdir=terraform output -raw cloud_sql_instance_name)"
export CLOUD_SQL_CONNECTION_NAME="$(terraform -chdir=terraform output -raw cloud_sql_connection_name)"

# Export project, location, GKE cluster, subnet, Artifact Registry, and external URL outputs.
export ARTIFACT_REGISTRY_REPO="$(terraform -chdir=terraform output -raw artifact_registry_repo)"
export ZONE="$(terraform -chdir=terraform output -raw cloud_zone)"
export REGION="$(terraform -chdir=terraform output -raw cloud_region)"
export PROJECT_ID="$(terraform -chdir=terraform output -raw project_id)"
export GKE_CLUSTER="$(terraform -chdir=terraform output -raw gke_cluster_name)"
export GUACAMOLE_URL="https://$(terraform -chdir=terraform output -raw external_url)/guacamole"
export SUBNET="$(terraform -chdir=terraform output -raw subnet)"
