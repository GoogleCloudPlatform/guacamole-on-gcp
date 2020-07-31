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

# !/bin/bash

FILE=./truststore.jks
CLIENT_STORE=./clientstore.jks
CLIENT_STORE_PASS=`cat /dev/random | tr -dc 'a-zA-Z0-9' | fold -w 16 | head -n 1`
CLIENT_STORE_PASS_FILE=./clientstore.pass
CERT_NAME=guac-db-client-cert
KEYTOOL=/usr/bin/keytool
OPENSSL=/usr/bin/openssl
GCLOUD=/usr/bin/gcloud

SERVERCERT=$(mktemp /tmp/server-ca.XXXXXX)
CLIENTCERT=$(mktemp /tmp/client-cert.XXXXXX)
CLIENTKEY=$(mktemp -u /tmp/client-key.XXXXXX)
CLIENTKEYPAIR=$(mktemp /tmp/client-keypair.XXXXXX)

if [ -z ${1} ]; then
    echo "Usage: bin/create-keystore.sh [CLOUD_SQL_INSTANCE]"
    exit 1
else
    rm $FILE 
    $GCLOUD sql instances describe ${1} --format="value(serverCaCert.cert)" > $SERVERCERT
    $KEYTOOL -import -alias cloudSQLServerCACert -file $SERVERCERT -keystore $FILE -storepass $CLIENT_STORE_PASS -noprompt
    rm $SERVERCERT

    # Generate Client Cert
    
    $GCLOUD sql ssl client-certs create $CERT_NAME $CLIENTKEY --instance=${1}
    $GCLOUD sql ssl client-certs describe $CERT_NAME --instance=${1} --format="value(cert)" > $CLIENTCERT
    
    $OPENSSL pkcs12 -export -in $CLIENTCERT -inkey $CLIENTKEY -out $CLIENTKEYPAIR -passout pass:$CLIENT_STORE_PASS -name mysqlclient
    rm $CLIENTCERT $CLIENTKEY $CLIENT_STORE_PASS_FILE
    
    $KEYTOOL -importkeystore -srckeystore $CLIENTKEYPAIR -destkeystore $CLIENT_STORE -srcstoretype pkcs12 \
        -alias mysqlclient -deststorepass $CLIENT_STORE_PASS -srcstorepass $CLIENT_STORE_PASS -noprompt
    
    echo $CLIENT_STORE_PASS >> ${CLIENT_STORE_PASS_FILE}
    
    rm $CLIENTKEYPAIR


    exit 0
fi