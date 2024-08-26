#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

function _export_auth0_env_vars {

  if [ -z "$GCP_CLUSTER_BASENAME" ]; then
    _error_msg "GCP_CLUSTER_BASENAME is not set, please run from a cluster directory or set the variable manually"
    exit 1
  fi

  AUTH0_TENANT=canton-network-validator-test.us.auth0.com
  AUTH_URL="https://${AUTH0_TENANT}"
  export AUTH_URL
  AUTH_JWKS_URL="${AUTH_URL}/.well-known/jwks.json"
  export AUTH_JWKS_URL
  AUTH_WELLKNOWN_URL="${AUTH_URL}/.well-known/openid-configuration"
  export AUTH_WELLKNOWN_URL

  auth0 login --domain ${AUTH0_TENANT} --client-id "$AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID" --client-secret "$AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET"
  auth0 tenants use ${AUTH0_TENANT}
  auth0_app=$(auth0 apps ls -r --json 2> /dev/null | jq '.[] | select(.name == "Validator app backend")')

  AUTH_CLIENT_SECRET=$(echo "$auth0_app" | jq -r '.client_secret')
  export AUTH_CLIENT_SECRET
  AUTH_CLIENT_ID=$(echo "$auth0_app" | jq -r '.client_id')
  export AUTH_CLIENT_ID
  LEDGER_API_ADMIN_USER="${AUTH_CLIENT_ID}@clients"
  export LEDGER_API_ADMIN_USER
  WALLET_UI_CLIENT_ID=$(auth0 apps ls -r --json 2> /dev/null | jq -r ".[] | select(.name == \"Wallet UI (Pulumi managed, $GCP_CLUSTER_BASENAME)\") | .client_id")
  export WALLET_UI_CLIENT_ID
  ANS_UI_CLIENT_ID=$(auth0 apps ls -r --json 2> /dev/null | jq -r ".[] | select(.name == \"ANS UI (Pulumi managed, $GCP_CLUSTER_BASENAME)\") | .client_id")
  export ANS_UI_CLIENT_ID
  LEDGER_API_AUTH_AUDIENCE="https://ledger_api.example.com"
  export LEDGER_API_AUTH_AUDIENCE
  WALLET_ADMIN_USER=$(auth0 users search --query email:"admin@validator.com" --json 2>/dev/null | jq -r '.[].user_id')
  export WALLET_ADMIN_USER
  VALIDATOR_AUTH_AUDIENCE="https://validator.example.com/api"
  export VALIDATOR_AUTH_AUDIENCE
}

function usage {
  echo "Usage: $0 [-a]"
  echo "  -a: Use this flag to enable authentication"
}

auth=0
while getopts 'ha' arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    a)
      auth=1
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

docker_gateway=$(docker network inspect bridge -f "{{range .IPAM.Config}}{{.Gateway}}{{end}}")

secret=$(curl -sSfL -X POST "http://127.0.0.1:5114/api/sv/v0/devnet/onboard/validator/prepare")

IMAGE_REPO=""
export IMAGE_REPO
IMAGE_TAG=$("${REPO_ROOT}/build-tools/get-snapshot-version")
export IMAGE_TAG

extra_flags=()
if [ $auth -eq 1 ]; then
  _export_auth0_env_vars
  extra_flags+=("-a")
fi

"${REPO_ROOT}/cluster/deployment/compose/start.sh" \
  -s "http://${docker_gateway}:5114" \
  -c "http://${docker_gateway}:5012" \
  -q "http://${docker_gateway}:5108" \
  -o "$secret" \
  -b \
  "${extra_flags[@]}" \
    >> "${REPO_ROOT}/log/compose.log" 2>&1

for c in validator participant; do
  docker logs -f compose-${c}-1 >> "${REPO_ROOT}/log/compose-${c}.clog" 2>&1 &
done
