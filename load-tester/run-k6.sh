#!/usr/bin/env bash
set -euo pipefail

if [ -z "$GCP_CLUSTER_BASENAME" ];
then
    echo "var GCP_CLUSTER_BASENAME: required"
    exit 1
fi

auth0_domain="https://canton-network-dev.us.auth0.com"
auth0_client_id="5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK"

base_hostname="$GCP_CLUSTER_BASENAME.network.canton.global"
wallet_url="https://wallet.validator1.$base_hostname"

prometheus_rw="https://prometheus.$base_hostname/api/v1/write"

npm run check # Static check test src
npm run build

K6_PROMETHEUS_RW_SERVER_URL="$prometheus_rw" k6 \
    -o experimental-prometheus-rw \
    -e WALLET_URL="$wallet_url" \
    -e AUTH0_DOMAIN="$auth0_domain" \
    -e AUTH0_CLIENT_ID="$auth0_client_id" \
    run dist/test/load-test.js
