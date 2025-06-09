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

ARG CLIENT_IMAGE=guacamole-client:latest

# Use official maven image for the build
FROM maven:3-eclipse-temurin-21 AS builder

# Use args to build radius auth extension such as
# `--build-arg BUILD_PROFILE=lgpl-extensions`
ARG BUILD_PROFILE

# Build environment variables
ENV \
    BUILD_DIR=/tmp/guacamole-docker-BUILD

# Add configuration scripts
COPY ./bin /opt/guacamole/bin 

# Copy the Google IAP extension source
COPY ./extensions/. ${BUILD_DIR}/extensions

# Run the build itself
RUN /opt/guacamole/bin/build-googleiap.sh ${BUILD_DIR}/extensions/guacamole-auth-googleiap /etc/guacamole

COPY ./guacamole.properties /etc/guacamole/guacamole.properties 

FROM $CLIENT_IMAGE

# Remove detault webapps
RUN rm -rf \
        /usr/local/tomcat/webapps/docs \
        /usr/local/tomcat/webapps/host-manager \
        /usr/local/tomcat/webapps/manager \
        /usr/local/tomcat/webapps/examples

# Add internalProxies via RemoteIpValve to server.xml
# - Will be possible via env, once PR merged: https://github.com/apache/guacamole-client/pull/489
COPY ./tomcat/conf/server.xml /usr/local/tomcat/conf

# This is where the build artifacts go in the runtime image
WORKDIR /etc/guacamole

COPY --from=builder /etc/guacamole/ .

# Start Guacamole under Tomcat, listening on 0.0.0.0:8080
EXPOSE 8080
CMD ["/opt/guacamole/bin/start.sh" ]

