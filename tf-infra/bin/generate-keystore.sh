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

#set -e
#trap 'rm -f $CLIENTCERT $CLIENTKEY $CLIENTKEYPAIR $INPUTFILE' EXIT

INPUTFILE=$(mktemp /tmp/input-json.XXXXXX)
cat - > "$INPUTFILE"

eval "$(jq -r '@sh "CLIENT_STORE_PASS=\(.keystore_password) CERT_NAME=\(.common_name)"' $INPUTFILE)"


FILE=truststore.jks
CLIENT_STORE=clientstore.jks

KEYTOOL=/usr/bin/keytool
OPENSSL=/usr/bin/openssl

CLIENTCERT=$(mktemp /tmp/client-cert.XXXXXX)
CLIENTKEY=$(mktemp -u /tmp/client-key.XXXXXX)
CLIENTKEYPAIR=$(mktemp /tmp/client-keypair.XXXXXX)

jq -r '.cert' ${INPUTFILE} > ${CLIENTCERT}
jq -r '.private_key' ${INPUTFILE} > ${CLIENTKEY}

rm -f $FILE || true
rm -f $CLIENT_STORE || true

jq -r '.server_ca_cert' ${INPUTFILE} | $KEYTOOL -import -alias cloudSQLServerCACert -keystore $FILE -storepass $CLIENT_STORE_PASS \
    -deststoretype jks -noprompt

$OPENSSL pkcs12 -export -in $CLIENTCERT -inkey $CLIENTKEY -out $CLIENTKEYPAIR -passout pass:$CLIENT_STORE_PASS -name mysqlclient
#rm $CLIENTCERT $CLIENTKEY

$KEYTOOL -importkeystore -srckeystore $CLIENTKEYPAIR -destkeystore $CLIENT_STORE -srcstoretype pkcs12 \
    -alias mysqlclient -deststorepass $CLIENT_STORE_PASS -deststoretype jks -srcstorepass $CLIENT_STORE_PASS -noprompt
#rm $CLIENTKEYPAIR

jq -n --arg truststore "$FILE" --arg clientstore "$CLIENT_STORE" '{"truststore":$truststore, "clientstore":$clientstore}'
