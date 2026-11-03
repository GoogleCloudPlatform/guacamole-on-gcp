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

# Default base runtime image aligned to Apache Guacamole 1.6.0 (tomcat:9-jdk21).
ARG CLIENT_IMAGE=guacamole/guacamole:1.6.0

# Stage 1: Build the Google IAP authentication extension using Maven and Eclipse Temurin JDK 21.
FROM maven:3-eclipse-temurin-21 AS builder

# Optional build profile argument for additional Guacamole extensions.
ARG BUILD_PROFILE

# Temporary directory used inside the builder stage to compile the extension.
ENV BUILD_DIR=/tmp/guacamole-docker-BUILD

# Copy the helper build script into /opt/guacamole/bin.
COPY ./bin /opt/guacamole/bin

# Copy the Google IAP extension source tree into the builder working directory.
COPY ./extensions/. ${BUILD_DIR}/extensions

# Compile, test, and package the extension and its runtime libraries into /etc/guacamole.
RUN /opt/guacamole/bin/build-googleiap.sh "${BUILD_DIR}/extensions/guacamole-auth-googleiap" /etc/guacamole

# Copy the rendered guacamole.properties into /etc/guacamole with guacamole user ownership.
COPY --chown=guacamole:guacamole ./guacamole.properties /etc/guacamole/guacamole.properties

# Stage 2: Assemble the production guacamole-client-iap runtime image on top of guacamole/guacamole:1.6.0.
FROM $CLIENT_IMAGE

# Guacamole 1.6.0 uses /etc/guacamole as the template directory to populate $GUACAMOLE_HOME at startup.
WORKDIR /etc/guacamole

# Copy the compiled extension JAR, runtime dependency JARs, and guacamole.properties with UID/GID 1001 ownership.
COPY --from=builder --chown=guacamole:guacamole /etc/guacamole/ .

# Expose Tomcat's unprivileged HTTP port and start Guacamole via the 1.6.0 entrypoint script.
EXPOSE 8080
CMD ["/opt/guacamole/bin/entrypoint.sh"]
