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

# Source directory containing the guacamole-auth-googleiap Maven project and target installation path.
BUILD_DIR="$1"
DESTINATION="$2"

# Create destination directories for Guacamole extensions and shared runtime libraries.
mkdir -p "${DESTINATION}/extensions"
mkdir -p "${DESTINATION}/lib"

# Compile, run unit tests + Apache RAT validation, and package the IAP extension JAR in batch mode.
cd "${BUILD_DIR}"
mvn -B package

# Verify the extension JAR was produced and copy both the extension and its runtime dependencies.
set -- target/guacamole-auth-googleiap-*.jar
if [ -f "$1" ]; then
    echo "googleiap Extension Built - copying to ${DESTINATION}"
    cp target/guacamole-auth-googleiap-*.jar "${DESTINATION}/extensions/"
    cp target/lib/*.jar "${DESTINATION}/lib/"
else
    echo "ERROR: guacamole-auth-googleiap JAR was not found in ${BUILD_DIR}/target" >&2
    exit 1
fi
