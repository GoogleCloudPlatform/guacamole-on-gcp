#!/bin/bash

set -e

eval "$(jq -r '@sh "EXTERNAL_IP=\(.externalip)"')"

CERT_DOMAIN=$(echo "$EXTERNAL_IP" | sed -e 's/\./-/g' -e 's/$/\.sslip\.io/')

jq -n --arg certdomain "$CERT_DOMAIN" '{"certdomain":$certdomain}'
