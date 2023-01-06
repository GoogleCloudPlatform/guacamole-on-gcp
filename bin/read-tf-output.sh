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

export CLOUD_SQL_INSTANCE=`terraform output -state tf-infra/terraform.tfstate -json db_address | jq -r .`
export ZONE=`terraform output -state tf-infra/terraform.tfstate -json cloud_zone | jq -r .`
export REGION=`terraform output -state tf-infra/terraform.tfstate -json cloud_region | jq -r .`
export DB_MGMT_VM=`terraform output -state tf-infra/terraform.tfstate -json db_mgmt_vm | jq -r .`
export PROJECT_ID=`terraform output -state tf-infra/terraform.tfstate -json project_id | jq -r .`
export GKE_CLUSTER=`terraform output -state tf-infra/terraform.tfstate -json gke_cluster_name | jq -r .`
export GUACAMOLE_URL="https://"`terraform output -state tf-infra/terraform.tfstate -json external_url | jq -r .`"/guacamole"
export SUBNET=`terraform output -state tf-infra/terraform.tfstate -json subnet | jq -r .`


echo "Database Root Password: `terraform output -state tf-infra/terraform.tfstate db_root_password`"
