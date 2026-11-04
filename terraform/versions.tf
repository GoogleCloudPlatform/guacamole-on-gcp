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

# Enforce modern Terraform CLI and provider versions for single-stage provisioning.
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    # Primary Google Cloud provider used for all GCP resources and IAM bindings.
    google = {
      source  = "hashicorp/google"
      version = ">= 5.6.0, < 7.0.0"
    }

    # Generates unique suffixes for Cloud SQL instances and random DB credentials.
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6.0"
    }

    # Renders local Kustomize properties, manifests, and sensitive secret files.
    local = {
      source  = "hashicorp/local"
      version = ">= 2.5.0"
    }

    # Reads active gcloud CLI / Cloud Shell account config when ADC lacks OpenID email scope.
    external = {
      source  = "hashicorp/external"
      version = ">= 2.3.0"
    }
  }
}
