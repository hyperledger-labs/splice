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

function _start_validator {

  sv_from_docker=$1
  sv_from_script=$2
  scan=$3
  sequencer=$4

  extra_flags=()
  if [ $auth -eq 1 ]; then
    _export_auth0_env_vars
    extra_flags+=("-a")
  fi
  if [ -n "$network_name" ]; then
    extra_flags+=("-n" "$network_name")
  fi

  _info "Curling ${sv_from_script}/api/sv/v0/devnet/onboard/validator/prepare for the secret"
  secret=$(curl -sSfL -X POST "${sv_from_script}/api/sv/v0/devnet/onboard/validator/prepare")

  _info "Starting validator"
  "${REPO_ROOT}/cluster/deployment/compose/start.sh" \
    -s "${sv_from_docker}" \
    -c "${scan}" \
    -q "${sequencer}" \
    -o "$secret" \
    -b \
    "${extra_flags[@]}" \
      >> "${REPO_ROOT}/log/compose.log" 2>&1 || _error "Failed to start validator, please check ${REPO_ROOT}/log/compose.log for details"

}

function usage {
  echo "Usage: $0 [-a] [-l] [-d] [-n <network_name>]"
  echo "  -a: Enable authentication"
  echo "  -l: Start the validator against a local SV (for integration tests)"
  echo "  -d: Use images from the DA-internal repository (default when using this script: use locally built images)"
  echo "  -n: Use a specific docker network"
}

auth=0
local_sv=0
da_repo=0
network_name=""
while getopts 'haldn:' arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    a)
      auth=1
      ;;
    l)
      local_sv=1
      ;;
    d)
      da_repo=1
      ;;
    n)
      network_name="${OPTARG}"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ $da_repo -eq 1 ]; then
  export IMAGE_REPO=us-central1-docker.pkg.dev/da-cn-shared/cn-images/
else
  export IMAGE_REPO=""
fi

IMAGE_TAG=$("${REPO_ROOT}/build-tools/get-snapshot-version")
export IMAGE_TAG

if [ $local_sv -eq 1 ]; then
  docker_gateway=$(docker network inspect bridge -f "{{range .IPAM.Config}}{{.Gateway}}{{end}}")
  _start_validator "http://${docker_gateway}:5114" "http://127.0.0.1:5114" "http://${docker_gateway}:5012" "http://${docker_gateway}:5108"
else
  if [ -z "$GCP_CLUSTER_HOSTNAME" ]; then
    _error_msg "GCP_CLUSTER_HOSTNAME is not set, please run from a cluster directory or set the variable manually."
    exit 1
  fi
    # TODO(#14481): non-0 migration ID
    _start_validator "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME" "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME" "https://scan.sv-2.$GCP_CLUSTER_HOSTNAME" "https://sequencer-0.sv-2.$GCP_CLUSTER_HOSTNAME"
fi

for c in validator participant; do
  docker logs -f compose-${c}-1 >> "${REPO_ROOT}/log/compose-${c}.clog" 2>&1 &
done
