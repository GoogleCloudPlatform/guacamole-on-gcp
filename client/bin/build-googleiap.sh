#!/bin/sh -e
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

BUILD_DIR="$1"
DESTINATION="$2"

#
# Create destination, if it does not yet exist
#

mkdir -p ${DESTINATION}/extensions
mkdir -p ${DESTINATION}/lib

#
# Build google iap jar
#

cd ${BUILD_DIR}
mvn package

# 
# Copy IAP auth extension and schema modifications
#

if [ -f target/guacamole-auth-googleiap*.jar ]; then
    echo "googleiap Extension Built - copying to ${DESTINATION}"
    cp target/guacamole-auth-googleiap*.jar ${DESTINATION}/extensions
    cp target/lib/*.jar ${DESTINATION}/lib
fi

