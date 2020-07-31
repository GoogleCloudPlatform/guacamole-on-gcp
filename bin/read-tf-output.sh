#!/bin/bash

export CLOUD_SQL_INSTANCE=`terraform output -state tf-infra/terraform.tfstate -json db_address | jq -r .`
export ZONE=`terraform output -state tf-infra/terraform.tfstate -json cloud_zone | jq -r .`
export REGION=`terraform output -state tf-infra/terraform.tfstate -json cloud_region | jq -r .`
export DB_MGMT_VM=`terraform output -state tf-infra/terraform.tfstate -json db_mgmt_vm | jq -r .`
export PROJECT_ID=`terraform output -state tf-infra/terraform.tfstate -json project_id | jq -r .`
export GKE_CLUSTER=`terraform output -state tf-infra/terraform.tfstate -json gke_cluster_name | jq -r .`
export GUACAMOLE_URL="https://"`terraform output -state tf-infra/terraform.tfstate -json external_url | jq -r .`"/guacamole"
export SUBNET=`terraform output -state tf-infra/terraform.tfstate -json subnet | jq -r .`


echo "Database Root Password: `terraform output -state tf-infra/terraform.tfstate db_root_password`"
