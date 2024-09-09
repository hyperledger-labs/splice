#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# issue a user friendly red error
function _error_msg(){
  # shellcheck disable=SC2145
  echo -e "\e[1;31mERROR: $@\e[0m" >&2
}

# issue a user friendly green informational message
function _info(){
  local first_line="INFO: "
  while read -r; do
    printf -- "\e[32;1m%s%s\e[0m\n" "${first_line:-     }" "${REPLY}"
    unset first_line
  done < <(echo -e "$@")
}

function usage() {
  echo "Usage: $0 -s <sponsor_sv_address> -o <onboarding_secret> -p <party_hint> [-a] [-b] [-c <scan_address>] [-q <sequencer_address>] [-n <network_name>] [-m <migration_id>] [-M]"
  echo "  -s <sponsor_sv_address>: The full URL of the sponsor SV"
  echo "  -o <onboarding_secret>: The onboarding secret to use. If not provided, it will be fetched from the sponsor SV (possible on DevNet only)"
  echo "  -p <party_hint>: The party hint to use for the validator operator, will also act as the participant identifier."
  echo "  -a: Use this flag to enable authentication"
  echo "  -c <scan_address>: The full URL of a Scan app. If not provided, it will be derived from the sponsor SV address."
  echo "  -n <network_name>: The name of an existing docker network to use. If not provided, the default network will be created used."
  echo "  -m <migration_id>: The migration ID to use. Must be a non-negative integer."
  echo "  -M: Use this flag when bumping the migration ID as part of a migration."

  echo ""
  echo "Testing flags:"
  echo "The following flags are usually used only for testing:"
  echo "  -b: Disable BFT reads&writes and trust a single SV."
  echo "  -c <scan_address>: The full URL of a Scan app. If not provided, it will be derived from the sponsor SV address."
  echo "  -q <sequencer_address>: The full URL of the sequencer. Must be provided if BFT reads&writes are disabled."
}

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

auth=0
trust_single=0
SPONSOR_SV_ADDRESS=""
SCAN_ADDRESS=""
ONBOARDING_SECRET=""
SEQUENCER_ADDRESS=""
network_name=""
migration_id=0
migrating=0
party_hint=""
while getopts 'has:c:t:o:n:bq:m:Mp:' arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    a)
      auth=1
      ;;
    s)
      SPONSOR_SV_ADDRESS="${OPTARG}"
      ;;
    c)
      SCAN_ADDRESS="${OPTARG}"
      ;;
    q)
      SEQUENCER_ADDRESS="${OPTARG}"
      ;;
    o)
      ONBOARDING_SECRET="${OPTARG}"
      ;;
    b)
      trust_single=1
      ;;
    n)
      network_name="${OPTARG}"
      ;;
    m)
      migration_id="${OPTARG}"
      ;;
    M)
      migrating=1
      ;;
    p)
      party_hint="${OPTARG}"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ -z "${SPONSOR_SV_ADDRESS}" ]; then
  _error_msg "Please provide the sponsor SV address"
  usage
  exit 1
fi

if [ -z "${ONBOARDING_SECRET}" ]; then
  # TODO(#14303): remove this auto-fetch of secrets. It's convenient while developing, so for now we're keeping it,
  # but to make the DevNet-MainNet gap smaller, we want to remove this.
  _info "Onboarding secret not provided, trying to automatically fetch one from the sponsor SV (possible on DevNet only)"
  set +e
  if ! ONBOARDING_SECRET=$(curl -sSfL -X POST "${SPONSOR_SV_ADDRESS}/api/sv/v0/devnet/onboard/validator/prepare"); then
    _error_msg "Failed to fetch onboarding secret from the sponsor SV automatically. If you are not on DevNet, please reach out to your sponsor SV and ask for an onboarding secret."
    exit 1
  fi
  set -e
fi

if [ -z "${party_hint}" ]; then
  _error_msg "Please provide the party hint"
  usage
  exit 1
fi

if [ -z "${SCAN_ADDRESS}" ]; then
  SCAN_ADDRESS=${SPONSOR_SV_ADDRESS//https:\/\/sv/https:\/\/scan}
  _info "SCAN address not provided, deriving it from the sponsor SV address: ${SCAN_ADDRESS}"
fi

if [ $trust_single -eq 1 ] && [ -z "${SEQUENCER_ADDRESS}" ]; then
  _error_msg "Please provide the sequencer address when BFT reads&writes are disabled"
  usage
  exit 1
fi

if [[ ! "${migration_id}" =~ ^[0-9]+$ ]]; then
  _error_msg "Migration ID must be a non-negative integer"
  usage
  exit 1
fi

export ONBOARDING_SECRET
export SPONSOR_SV_ADDRESS
export SCAN_ADDRESS
export SEQUENCER_ADDRESS
export MIGRATION_ID=${migration_id}
export PARTICIPANT_IDENTIFIER=${party_hint}
export PARTY_HINT=${party_hint}

# TODO(#14303): release tag should be injected by the release pipeline
# IMAGE_TAG=$("${REPO_ROOT}/build-tools/get-snapshot-version")
# export IMAGE_TAG

if [ -z "${SPLICE_INSTANCE_NAMES:-}" ]; then
  splice_instance_names=$(curl -sSLf "${SCAN_ADDRESS}/api/scan/v0/splice-instance-names")
else
  # TODO(#14303): remove this once the migration base version supports the splice-instance-names endpoint
  splice_instance_names=${SPLICE_INSTANCE_NAMES}
fi
CN_APP_UI_NETWORK_NAME=$(echo "${splice_instance_names}" | jq -r '.network_name')
export CN_APP_UI_NETWORK_NAME
CN_APP_UI_NETWORK_FAVICON_URL=$(echo "${splice_instance_names}" | jq -r '.network_favicon_url')
export CN_APP_UI_NETWORK_FAVICON_URL
CN_APP_UI_AMULET_NAME=$(echo "${splice_instance_names}" | jq -r '.amulet_name')
export CN_APP_UI_AMULET_NAME
CN_APP_UI_AMULET_NAME_ACRONYM=$(echo "${splice_instance_names}" | jq -r '.amulet_name_acronym')
export CN_APP_UI_AMULET_NAME_ACRONYM
CN_APP_UI_NAME_SERVICE_NAME=$(echo "${splice_instance_names}" | jq -r '.name_service_name')
export CN_APP_UI_NAME_SERVICE_NAME
CN_APP_UI_NAME_SERVICE_NAME_ACRONYM=$(echo "${splice_instance_names}" | jq -r '.name_service_name_acronym')
export CN_APP_UI_NAME_SERVICE_NAME_ACRONYM

extra_compose_files=()
if [ $auth -ne 1 ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-disable-auth.yaml")
fi
if [ $trust_single -eq 1 ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-trust-single.yaml")
fi
if [ $migrating -eq 1 ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-migrate.yaml")
fi
if [ -n "${network_name}" ]; then
  # TODO(#14303): we take a network_name argument, but the name "onvpn" is hardcoded in the compose-onvpn-network.yaml file.
  # I don't think there's a way to parameterize it, so we should either auto-generate that, or at least parse it and confirm that
  # it declares the same name as the network_name argument.
  extra_compose_files+=("-f" "${script_dir}/compose-onvpn-network.yaml")
  export DOCKER_NETWORK="${network_name}"
fi
docker compose -f "${script_dir}/compose.yaml" "${extra_compose_files[@]}" up -d
