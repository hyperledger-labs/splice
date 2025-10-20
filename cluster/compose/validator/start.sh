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
  echo "Usage: $0 -s <sponsor_sv_address> -o <onboarding_secret> -p <party_hint> -m <migration_id> [-a] [-b] [-c <scan_address>] [-C <host_scan_address>] [-q <sequencer_address>] [-n <network_name>] [-M] [-i <identities_dump>] [-P <participant_id>] [-w] [-l] [-E]"
  echo "  -s <sponsor_sv_address>: The full URL of the sponsor SV"
  echo "  -o <onboarding_secret>: The onboarding secret to use. May be empty (\"\") if you are already onboarded."
  echo "  -p <party_hint>: The party hint to use for the validator operator, by default also your participant identifier."
  echo "  -P <participant_id>: The participant identifier."
  echo "  -a: Use this flag to enable authentication"
  echo "  -c <scan_address>: The full URL of a Scan app. If not provided, it will be derived from the sponsor SV address."
  echo "  -C <host_scan_address>: An optional alternative URL of a Scan app, when accessed from the host as opposed to a container."
  echo "  -n <network_name>: The name of an existing docker network to use. If not provided, the default network will be created used."
  echo "  -m <migration_id>: The migration ID to use. Must be a non-negative integer."
  echo "  -M: Use this flag when bumping the migration ID as part of a migration."
  echo "  -i <identities_dump>: restore identities from a dump file. Requires a new participant identifier to be provided."
  echo "  -w: Wait for the validator to be fully up and running before returning."
  echo "  -l: Connect participant and validator also to docker network compose-sv_splice-sv-public, to use an SV deployed locally on docker compose"
  echo "      Also implies -s, -c and -C to be the defaults for such a deployment."

  echo ""
  echo "Testing flags:"
  echo "The following flags are usually used only for testing:"
  echo "  -b: Disable BFT reads&writes and trust a single SV."
  echo "  -c <scan_address>: The full URL of a Scan app. If not provided, it will be derived from the sponsor SV address."
  echo "  -q <sequencer_address>: The full URL of the sequencer. Must be provided if BFT reads&writes are disabled."
  echo "  -E: Use this flag to bind the Nginx proxy to 0.0.0.0 (external access) instead of 127.0.0.1 (default)."
}

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

auth=0
trust_single=0
SPONSOR_SV_ADDRESS=""
SCAN_ADDRESS=""
host_scan_address=""
ONBOARDING_SECRET="undefined"
SEQUENCER_ADDRESS=""
network_name=""
migration_id=""
migrating=0
party_hint=""
participant_id=""
restore_identities_dump=""
local_compose_sv=0
wait=0
HOST_BIND_IP="127.0.0.1"

while getopts 'has:c:C:t:o:n:bq:m:Mp:P:i:wlE' arg; do
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
    C)
      host_scan_address="${OPTARG}"
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
    P)
      participant_id="${OPTARG}"
      ;;
    i)
      restore_identities_dump="${OPTARG}"
      ;;
    w)
      wait=1
      ;;
    l)
      local_compose_sv=1
      ;;
    E)
      HOST_BIND_IP="0.0.0.0"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ "${local_compose_sv}" -eq 1 ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-local-compose-sv.yaml")
  if [ -z "${SCAN_ADDRESS}" ]; then
    SCAN_ADDRESS="http://scan:5012"
    _info "Using default scan address for local docker-compose based SV: ${SCAN_ADDRESS}"
  fi
  if [ -z "${host_scan_address}" ]; then
    host_scan_address="http://scan.localhost:8080"
    _info "Using default host scan address for local docker-compose based SV: ${host_scan_address}"
  fi
  if [ -z "${SPONSOR_SV_ADDRESS}" ]; then
    SPONSOR_SV_ADDRESS="http://sv-app:5014"
    _info "Using default sponsor SV address for local docker-compose based SV: ${SPONSOR_SV_ADDRESS}"
  fi
fi

if [ -z "${SPONSOR_SV_ADDRESS}" ]; then
  _error_msg "Please provide the sponsor SV address"
  usage
  exit 1
fi

if [ "${ONBOARDING_SECRET}" == "undefined" ]; then
  _error_msg "Please provide the onboarding secret. If you are already onboarded, you may leave the secret value itself empty, i.e. specify \`-o \"\"\`"
  usage
  exit 1
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

if [ -z "${migration_id}" ]; then
  _error_msg "Please provide a migration id, you can find the current migration id at https://sync.global/sv-network/ (make sure you select the right network)"
  usage
  exit 1
fi

if [[ ! "${migration_id}" =~ ^[0-9]+$ ]]; then
  _error_msg "Migration ID must be a non-negative integer"
  usage
  exit 1
fi

if [ -n "${restore_identities_dump}" ] && [ -z "${participant_id}" ]; then
  _error_msg "Please provide a new participant identifier when restoring identities from a dump file"
  usage
  exit 1
fi

export ONBOARDING_SECRET
export SPONSOR_SV_ADDRESS
export SCAN_ADDRESS
export SEQUENCER_ADDRESS
export MIGRATION_ID=${migration_id}
export PARTY_HINT=${party_hint}
if [ -n "${participant_id}" ]; then
  export PARTICIPANT_IDENTIFIER=${participant_id}
else
  export PARTICIPANT_IDENTIFIER=${party_hint}
fi

if [ -z "${IMAGE_TAG:-}" ]; then
  if [ ! -f "${script_dir}/../../VERSION" ]; then
    _error_msg "Could not derive image tags automatically, ${script_dir}/../../VERSION is missing. Please make sure that file exists, or export an image tag in IMAGE_TAG"
    exit 1
  else
    IMAGE_TAG=$(cat "${script_dir}/../../VERSION")
    _info "Using version ${IMAGE_TAG}"
    export IMAGE_TAG
  fi
fi

for i in {1..30}; do
  if [ -z "${host_scan_address}" ]; then
    splice_instance_names=$(curl -sSLf "${SCAN_ADDRESS}/api/scan/v0/splice-instance-names") && break
  else
    splice_instance_names=$(curl -sSLf "${host_scan_address}/api/scan/v0/splice-instance-names") && break
  fi
  _info "Failed to fetch splice_instance_names, retrying in 10 seconds (retry #$i)"
  sleep 10
done
if [ -z "$splice_instance_names" ]; then
  _error_msg "Failed to fetch splice_instance_names"
  exit 1
fi

SPLICE_APP_UI_NETWORK_NAME=$(echo "${splice_instance_names}" | jq -r '.network_name')
export SPLICE_APP_UI_NETWORK_NAME
SPLICE_APP_UI_NETWORK_FAVICON_URL=$(echo "${splice_instance_names}" | jq -r '.network_favicon_url')
export SPLICE_APP_UI_NETWORK_FAVICON_URL
SPLICE_APP_UI_AMULET_NAME=$(echo "${splice_instance_names}" | jq -r '.amulet_name')
export SPLICE_APP_UI_AMULET_NAME
SPLICE_APP_UI_AMULET_NAME_ACRONYM=$(echo "${splice_instance_names}" | jq -r '.amulet_name_acronym')
export SPLICE_APP_UI_AMULET_NAME_ACRONYM
SPLICE_APP_UI_NAME_SERVICE_NAME=$(echo "${splice_instance_names}" | jq -r '.name_service_name')
export SPLICE_APP_UI_NAME_SERVICE_NAME
SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM=$(echo "${splice_instance_names}" | jq -r '.name_service_name_acronym')
export SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM

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
  export DOCKER_NETWORK="${network_name}"
  if ! docker compose -f "${script_dir}/compose.yaml" -f "${script_dir}/compose-onvpn-network.yaml" config -q 2>/dev/null; then
    _error_msg "When using a custom network name, please edit compose-onvpn-network.yaml to use that name instead of 'onvpn'"
    exit 1
  fi
  extra_compose_files+=("-f" "${script_dir}/compose-onvpn-network.yaml")
fi
if [ -n "${restore_identities_dump}" ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-restore-from-id.yaml")
  export VALIDATOR_NEW_PARTICIPANT_IDENTIFIER=${PARTICIPANT_IDENTIFIER}
  export VALIDATOR_PARTICIPANT_IDENTITIES_DUMP=${restore_identities_dump}
fi
if [ "${local_compose_sv}" -eq 1 ]; then
  extra_compose_files+=("-f" "${script_dir}/compose-local-compose-sv.yaml")
fi
extra_compose_files+=("-f" "${script_dir}/compose-traffic-topups.yaml")
extra_args=()
if [ $wait -eq 1 ]; then
  extra_args+=("--wait" "--wait-timeout" "600")
fi

export HOST_BIND_IP

docker compose -f "${script_dir}/compose.yaml" "${extra_compose_files[@]}" up -d "${extra_args[@]}"
