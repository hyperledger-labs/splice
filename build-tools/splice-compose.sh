#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
SCRIPTNAME=${0##*/}

declare -A subcommand_whitelist

DEFAULT_AUDIENCE="https://canton.network.global"
VALIDATOR_DIR="${SPLICE_ROOT}/cluster/compose/validator"
SV_DIR="${SPLICE_ROOT}/cluster/compose/sv"

function _export_auth0_env_vars {

  if [ -z "$GCP_CLUSTER_BASENAME" ]; then
    _error_msg "GCP_CLUSTER_BASENAME is not set, please run from a cluster directory or set the variable manually"
    exit 1
  fi

  AUTH0_TENANT=canton-network-test.us.auth0.com
  AUTH_URL="https://${AUTH0_TENANT}"
  export AUTH_URL
  AUTH_JWKS_URL="${AUTH_URL}/.well-known/jwks.json"
  export AUTH_JWKS_URL
  AUTH_WELLKNOWN_URL="${AUTH_URL}/.well-known/openid-configuration"
  export AUTH_WELLKNOWN_URL

  auth0 login --domain ${AUTH0_TENANT} --client-id "$AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID" --client-secret "$AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET"
  auth0 tenants use ${AUTH0_TENANT}
  auth0_app=$(auth0 apps ls -r --json 2> /dev/null | jq '.[] | select(.name == "Docker-Compose Validator App")')

  VALIDATOR_AUTH_CLIENT_SECRET=$(echo "$auth0_app" | jq -r '.client_secret')
  export VALIDATOR_AUTH_CLIENT_SECRET
  VALIDATOR_AUTH_CLIENT_ID=$(echo "$auth0_app" | jq -r '.client_id')
  export VALIDATOR_AUTH_CLIENT_ID
  LEDGER_API_ADMIN_USER="${VALIDATOR_AUTH_CLIENT_ID}@clients"
  export LEDGER_API_ADMIN_USER
  WALLET_UI_CLIENT_ID=$(auth0 apps ls -r --json 2> /dev/null | jq -r ".[] | select(.name == \"Docker-Compose Wallet UI\") | .client_id")
  export WALLET_UI_CLIENT_ID
  ANS_UI_CLIENT_ID=$(auth0 apps ls -r --json 2> /dev/null | jq -r ".[] | select(.name == \"Docker-Compose ANS UI\") | .client_id")
  export ANS_UI_CLIENT_ID
  LEDGER_API_AUTH_AUDIENCE="https://ledger_api.example.com"
  export LEDGER_API_AUTH_AUDIENCE
  WALLET_ADMIN_USER=$(auth0 users search --query email:"admin@compose-validator.com" --json 2>/dev/null | jq -r '.[].user_id')
  export WALLET_ADMIN_USER
  VALIDATOR_AUTH_AUDIENCE="https://validator.example.com/api"
  export VALIDATOR_AUTH_AUDIENCE
}

function _do_start_validator {
  "${VALIDATOR_DIR}/start.sh" \
    "$@" \
      | tee -a "${SPLICE_ROOT}/log/compose.log" 2>&1 || _error "Failed to start validator, please check ${SPLICE_ROOT}/log/compose.log for details"

  for c in validator participant nginx; do
    docker logs -f splice-validator-${c}-1 >> "${SPLICE_ROOT}/log/compose-${c}.clog" 2>&1 &
  done

  if [ "$wait" -eq 1 ]; then
    # start.sh is idempotent, so running it again with -w should not interfere with the deployment, only wait for it to be ready
    _info "Waiting for the validator to be ready"
    "${VALIDATOR_DIR}/start.sh" \
      "$@" \
      "-w" \
        | tee -a "${SPLICE_ROOT}/log/compose-wait.log" 2>&1 || _error "Validator failed to become ready"
  fi

}

function _start_validator {

  sv_from_docker=$1
  sv_from_script=$2
  scan=$3
  sequencer=$4

  LEDGER_API_AUTH_AUDIENCE="$DEFAULT_AUDIENCE"
  export LEDGER_API_AUTH_AUDIENCE
  VALIDATOR_AUTH_AUDIENCE="$DEFAULT_AUDIENCE"
  export VALIDATOR_AUTH_AUDIENCE

  extra_flags=()
  if [ "$auth" -eq 1 ]; then
    _export_auth0_env_vars
    extra_flags+=("-a")
  fi
  if [ -n "$network_name" ]; then
    extra_flags+=("-n" "$network_name")
  fi
  if [ "$migrating" -eq 1 ]; then
    extra_flags+=("-M")
  fi
  if [ -n "$restore_identities_dump" ]; then
    extra_flags+=("-i" "$restore_identities_dump")
  fi
  if [ -n "$participant_id" ]; then
    extra_flags+=("-P" "$participant_id")
  fi
  if [ "$trust_single" -eq 1 ]; then
    extra_flags+=("-b")
  fi

  secret_url="${sv_from_script}/api/sv/v0/devnet/onboard/validator/prepare"
  _info "Curling ${secret_url} for the secret"
  secret=""
  for i in {1..30}; do
    secret=$(curl --connect-timeout 10 --max-time 20 -sfL --show-error -X POST "${secret_url}") && break
    _warning "Failed to fetch secret, retrying in 10 seconds"
    sleep 10
  done

  if [ -z "$secret" ]; then
    _error "Failed to fetch secret"
    exit 1
  fi

  mkdir -p "${SPLICE_ROOT}/log"

  _info "Starting validator"
  args=( \
    "-s" "${sv_from_docker}" \
    "-c" "${scan}" \
    "-q" "${sequencer}" \
    "-o" "${secret}" \
    "-m" "${migration_id}" \
    "-p" "${party_hint}" \
  )

  all_args=( "${args[@]}" "${extra_flags[@]}" )
  _do_start_validator "${all_args[@]}"
}

function _stop_validator {

  "${VALIDATOR_DIR}/stop.sh"

  if [ "$delete_volumes" -eq 1 ]; then
    _info "Deleting the volume data"
    docker volume rm splice-validator_postgres-splice > /dev/null 2>&1 || true
    docker volume rm splice-validator_domain-upgrade-dump > /dev/null 2>&1 || true
  fi
}

function _usage {
  _info "Usage: $SCRIPTNAME {subcommand} [options...]"

  echo ""
  _info "Subcommands:"
  for subcommand in "${!subcommand_whitelist[@]}"; do
    _info "  $subcommand - ${subcommand_whitelist[$subcommand]}"
    [[ $(type -t "usage_$subcommand") == function ]] && "usage_$subcommand"
    echo ""
  done
}

subcommand_whitelist[help]='show help'
function subcmd_help {
  _usage
}

subcommand_whitelist[start]='start a validator'

function subcmd_start {
  auth=0
  local_sv=0
  da_repo=0
  network_name=""
  wait=0
  migration_id=0
  migrating=0
  IMAGE_TAG=$("${SPLICE_ROOT}/build-tools/get-snapshot-version")
  restore_identities_dump=""
  party_hint="$(whoami)-composeValidator-1"
  participant_id=""
  trust_single=0
  while getopts 'haldn:m:Mwt:i:p:P:b' arg; do
    case ${arg} in
      h)
        subcmd_help
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
      m)
        migration_id="${OPTARG}"
        ;;
      M)
        migrating=1
        ;;
      w)
        wait=1
        ;;
      t)
        IMAGE_TAG="${OPTARG}"
        da_repo=1
        ;;
      i)
        restore_identities_dump="${OPTARG}"
        ;;
      p)
        party_hint="${OPTARG}"
        ;;
      P)
        participant_id="${OPTARG}"
        ;;
      b)
        trust_single=1
        ;;
      ?)
        subcmd_help
        exit 1
        ;;
    esac
  done

  if [[ ! "${migration_id}" =~ ^[0-9]+$ ]]; then
    _error_msg "Migration ID must be a non-negative integer"
    exit 1
  fi

  if [ $da_repo -eq 1 ]; then
    export IMAGE_REPO="${CACHE_DEV_DOCKER_REGISTRY}/"
  else
    # Locally built images (the default when using this script)
    export IMAGE_REPO=""
  fi

  export IMAGE_TAG

  if [ $local_sv -eq 1 ]; then
    docker_gateway=$(docker network inspect bridge -f "{{range .IPAM.Config}}{{.Gateway}}{{end}}")
    _start_validator "http://${docker_gateway}:5114" "http://127.0.0.1:5114" "http://${docker_gateway}:5012" "http://${docker_gateway}:5108"
  else
    if [ -z "$GCP_CLUSTER_HOSTNAME" ]; then
      _error_msg "GCP_CLUSTER_HOSTNAME is not set, please run from a cluster directory or set the variable manually."
      exit 1
    fi
      _start_validator "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME" "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME" "https://scan.sv-2.$GCP_CLUSTER_HOSTNAME" "https://sequencer-${migration_id}.sv-2.$GCP_CLUSTER_HOSTNAME"
  fi
}
function usage_start {
  _info "    Options: [-a] [-l] [-d] [-n <network_name>] [-m <migration_id>] [-M] [-w] [-t <image_tag>] [-i <identities_dump>] [-p <party_hint>] [-P <participant_id>]"
  _info "      -a: Enable authentication"
  _info "      -l: Start the validator against a local SV (for integration tests). Default is against a cluster determined by GCP_CLUSTER_HOSTNAME"
  _info "      -d: Use images from the DA-internal repository (default: use locally built images)"
  _info "      -n: Use a specific docker network"
  _info "      -m: Currently active Migration ID on the network"
  _info "      -M: Use this flag when bumping the migration ID as part of a migration"
  _info "      -w: Wait for the validator to be ready"
  _info "      -t: Use a specific image tag (default: current snapshot). Implies -d"
  _info "      -i <identities_dump>: restore identities from a dump file"
  _info "      -p <party_hint>: party hint (by default, <local_user>-composeValidator-1)"
  _info "      -P <participant_id>: participant identifier (by default, identical to the party hint)"
  _info "      -b: Disable BFT reads&writes and trust a single SV."
}

subcommand_whitelist[stop]='stop a validator'
function subcmd_stop {

  delete_volumes=0
  force=0
  while getopts 'hDf' arg; do
    case ${arg} in
      h)
        subcmd_help
        exit 0
        ;;
      D)
        delete_volumes=1
        ;;
      f)
        force=1
        ;;
      ?)
        subcmd_help
        exit 1
        ;;
    esac
  done

  if [ $delete_volumes -eq 1 ] && [ $force -eq 0 ]; then
    _confirm "Are you sure you want to delete the volumes? This will delete all data stored in the database."
  fi

  _stop_validator
}
function usage_stop {
  _info "    Options: [-D] [-f]"
  _info "      -D: Also delete volume data. Warning: completely nukes the validator."
  _info "      -f: When combined with -D, skips the confirmation prompt."
}

subcommand_whitelist[start_network]='Starts a full network (one SV + one validator)'
function subcmd_start_network {

  wait=0
  while getopts 'hw' arg; do
    case ${arg} in
      h)
        subcmd_help
        exit 0
        ;;
      w)
        wait=1
        ;;
      ?)
        subcmd_help
        exit 1
        ;;
    esac
  done

  IMAGE_TAG=$("${SPLICE_ROOT}/build-tools/get-snapshot-version")
  export IMAGE_TAG
  # Locally built images (the default when using this script)
  export IMAGE_REPO=""

  _info "Starting SV"
  "${SV_DIR}/start.sh"

  for c in validator participant scan sv-app sequencer-mediator nginx; do
    docker logs -f splice-sv-${c}-1 >> "${SPLICE_ROOT}/log/compose-sv-${c}.clog" 2>&1 &
  done

  # We must wait for the SV to be ready before starting the validator
  # start.sh is idempotent, so running it again with -w should not interfere with the deployment, only wait for it to be ready
  _info "Waiting for the SV to be ready"
  "${SV_DIR}/start.sh" -w

  get_secret_url="sv.localhost:8080/api/sv/v0/devnet/onboard/validator/prepare"
  _info "Curling $get_secret_url for the secret"
  secret=""
  # For reasons I couldn't understand, on CCI the "docker compose up --wait" seems
  # to return before the services are actually ready, so we retry fetching the onboarding
  # secret until it actually succeeds
  for i in {1..30}; do
    secret=$(curl -sfL --show-error -X POST "${get_secret_url}") && break
    _warning "Failed to fetch secret, retrying in 10 seconds"
    sleep 10
  done
  if [ -z "$secret" ]; then
    _error "Failed to fetch secret"
  fi
  # And also wait for the readiness endpoint on Scan
  for i in {1..30}; do
    curl -sf "scan.localhost:8080/api/scan/readyz" && break
    echo -n "."
    sleep 10
  done
  curl -sf "scan.localhost:8080/api/scan/readyz" || _error "Scan is not ready after 5 minutes" || exit 1

  _info "Starting validator"
  _do_start_validator -l -o "$secret" -p "local-composeValidator-1" -m 0

  _info "The full network is ready"
}
function usage_start_network {
  _info "    Options: [-w]"
  _info "      -w: Wait also for the validator to be ready (for the SV we must always wait before starting the validator)"
}

subcommand_whitelist[stop_network]='Stop a full network, started with start_network'
function subcmd_stop_network {

  delete_volumes=0
  force=0
  while getopts 'hDf' arg; do
    case ${arg} in
      h)
        subcmd_help
        exit 0
        ;;
      D)
        delete_volumes=1
        ;;
      f)
        force=1
        ;;
      ?)
        subcmd_help
        exit 1
        ;;
    esac
  done

  if [ $delete_volumes -eq 1 ] && [ $force -eq 0 ]; then
    _confirm "Are you sure you want to delete the volumes? This will delete all data stored in the database."
  fi

  _stop_validator

  "${SV_DIR}/stop.sh"

  if [ $delete_volumes -eq 1 ]; then
    docker volume rm splice-sv_postgres-splice-sv > /dev/null 2>&1 || true
  fi
}
function usage_stop_network {
  _info "    Options: [-D] [-f]"
  _info "      -D: Also delete volume data. Warning: completely nukes the validator."
  _info "      -f: When combined with -D, skips the confirmation prompt."
}


subcommand_whitelist[test_before_migration]='prepare the validator for the hard domain migration test'
function subcmd_test_before_migration {

  USER=alice

  VALIDATOR_AUTH_AUDIENCE="$DEFAULT_AUDIENCE"
  export VALIDATOR_AUTH_AUDIENCE
  TOKEN=$("${VALIDATOR_DIR}/get-token.py" $USER)

  onboarded=0
  # Onboard user, with retries because we need to wait for traffic to be available in order for it to succeed
  for i in {1..30}; do
    _info "Onboarding $USER"
    curl -sS 'http://wallet.localhost/api/validator/v0/register' \
      -X 'POST' \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -o /dev/null

    _info "Confirming user status"
    onboarded=$(curl -sS 'http://wallet.localhost/api/validator/v0/wallet/user-status' \
      -H "Authorization: Bearer $TOKEN" | jq '.user_onboarded')
    if [ "$onboarded" == "true" ]; then
      onboarded=1
      break
    fi
    _info "Onboarding failed, sleeping for 10 seconds and retrying"
    sleep 10
  done
  if [ "$onboarded" -eq 0 ]; then
    _error "Onboarding failed"
  fi

  _info "Tap some amulet"
  curl -sS 'http://wallet.localhost/api/validator/v0/wallet/tap' \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    --data-raw '{"amount":"100.0"}' \
    -o /dev/null

  _info "Check the balance"
  balance=$(curl -sS 'http://wallet.localhost/api/validator/v0/wallet/balance' \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' | jq -r '.effective_unlocked_qty')

  _info "Balance is $balance"

  _info "Waiting for domain migration dump to be created"
  # wait (for up to an hour) for the log to report that the domain migration dump has been written
  done=0
  # shellcheck disable=SC2034
  for i in {1..360}; do
    echo -n "."
    if docker exec splice-validator-validator-1 ls -l /domain-upgrade-dump/domain_migration_dump.json; then
      done=1
      break
    fi
    sleep 10
  done
  if [ $done -eq 0 ]; then
    _error "Timeout waiting for domain migration dump to be written"
  fi
  _info "Domain migration dump was written"

  _info "Content of the domain migration dump directory:"
  docker exec splice-validator-validator-1 ls -l /domain-upgrade-dump
}

subcommand_whitelist[test_after_migration]='test the validator after the hard domain migration'
function subcmd_test_after_migration {

  VALIDATOR_AUTH_AUDIENCE="$DEFAULT_AUDIENCE"
  export VALIDATOR_AUTH_AUDIENCE
  USER=alice
  TOKEN=$("${VALIDATOR_DIR}/get-token.py" $USER)

  onboarded=0
    # Wait until alice gets re-onboarded, which requires traffic to be available in order for it to succeed
  for i in {1..30}; do
    _info "Confirming user status"
    onboarded=$(curl -sS 'http://wallet.localhost/api/validator/v0/wallet/user-status' \
      -H "Authorization: Bearer $TOKEN" | jq '.user_onboarded')
    if [ "$onboarded" == "true" ]; then
      onboarded=1
      break
    fi
    _info "Alice not yet re-onboarded, sleeping for 10 seconds and retrying"
    sleep 10
  done
  if [ "$onboarded" -eq 0 ]; then
    _error "Onboarding failed"
  fi

  amulet_price=$(curl --location "https://scan.sv-2.${GCP_CLUSTER_HOSTNAME}/api/scan/v0/open-and-issuing-mining-rounds" \
    --header 'Content-Type: application/json' \
    --data '{"cached_open_mining_round_contract_ids" : [], "cached_issuing_round_contract_ids" : []}' \
    | jq -r '.open_mining_rounds  | to_entries | .[0] | .value.contract.payload.amuletPrice')
  # we tapped $100, so we expect the balance to be at least 99
  min_expected_balance=$(echo "99 / $amulet_price" | bc)

  _info "Check the balance"
  for i in {1..30}; do
    balance=$(curl -sS 'http://wallet.localhost/api/validator/v0/wallet/balance' \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' | jq -r '.effective_unlocked_qty')

    if [ -z "$balance" ] || (( $(echo "$balance < $min_expected_balance" | bc -l) )); then
      _info "Balance is $balance, expected at least $min_expected_balance (\$99 at amulet price of $amulet_price). Retrying."
    else
      _info "Balance is $balance"
      break
    fi
  done

  if [ -z "$balance" ] || (( $(echo "$balance < $min_expected_balance" | bc -l) )); then
    _error "Balance is $balance, expected at least $min_expected_balance (\$99 at amulet price of $amulet_price). Out of retries."
  fi
}

subcommand_whitelist[dump_volume]='dump a docker volume to a tarball'
function subcmd_dump_volume {

  if [ $# -lt 2 ]; then
    _error "Usage: $SCRIPTNAME dump_volume <volume_name> <tarball_path>"
  fi

  volume_name=$1
  tarball_path=$2

  if [ -z "$(docker volume ls -q -f name="${volume_name}")" ]; then
    _error "Volume $volume_name does not exist"
  fi

  mkdir -p "$(dirname "$tarball_path")"

  tarball_dir=$(dirname "$(realpath "$tarball_path")")
  tarball_name=$(basename "$tarball_path")

  _info "Content of volume $volume_name:"
  docker run --rm -v "${volume_name}:/volume" alpine sh -c "ls -l /volume"

  _info "Dumping the volume $volume_name to $tarball_path"
  docker run --rm -v "${volume_name}:/volume" -v "${tarball_dir}:/backup" alpine sh -c "tar -cf /backup/${tarball_name} -C /volume ."

  _info "Volume $volume_name dumped to $tarball_dir/$tarball_name"
  _info "tarball path:"
  ls "$tarball_path"
  _info "tarball contents:"
  tar -tf "$tarball_path"
}

subcommand_whitelist[restore_volume]='restore a docker volume from a tarball'
function subcmd_restore_volume {

  if [ $# -lt 2 ]; then
    _error "Usage: $SCRIPTNAME restore_volume <volume_name> <tarball_path>"
  fi

  volume_name=$1
  tarball_path=$2

  tarball_dir=$(dirname "$(realpath "$tarball_path")")
  tarball_name=$(basename "$tarball_path")

  _info "tarball path:"
  ls "$tarball_path"
  _info "tarball contents:"
  tar -tf "$tarball_path"

  docker run --rm -v "${volume_name}:/volume" -v "${tarball_dir}:/backup" alpine sh -c "tar -C /volume -xvf /backup/${tarball_name}"

  _info "Content of volume $volume_name:"
  docker run --rm -v "${volume_name}:/volume" alpine sh -c "ls -l /volume"
}


subcommand_whitelist[backup_node]='backup the validator node'
function subcmd_backup_node {

  if [ $# -lt 1 ]; then
    _error "Usage: $SCRIPTNAME backup_node <backup_dir>"
  fi

  backup_dir=$1
  mkdir -p "$backup_dir"

  docker exec -i splice-validator-postgres-splice-1 pg_dump -U cnadmin validator > "${backup_dir}"/validator-"$(date -u +"%Y-%m-%dT%H:%M:%S%:z")".dump
  active_participant_db=$(docker exec splice-validator-participant-1 bash -c 'echo $CANTON_PARTICIPANT_POSTGRES_DB')
  docker exec splice-validator-postgres-splice-1 pg_dump -U cnadmin "${active_participant_db}" > "${backup_dir}"/"${active_participant_db}"-"$(date -u +"%Y-%m-%dT%H:%M:%S%:z")".dump
}

subcommand_whitelist[restore_node]='restore the validator node'
function subcmd_restore_node {

  if [ $# -lt 3 ]; then
    _error "Usage: $SCRIPTNAME restore_node <validator_backup_file> <participant_backup_file> <migration_id>"
  fi

  validator_backup_file=$1
  participant_backup_file=$2
  MIGRATION_ID=$3

  export MIGRATION_ID
  export IMAGE_TAG=
  export ONBOARDING_SECRET=
  export SCAN_ADDRESS=
  export SPONSOR_SV_ADDRESS=
  export TARGET_CLUSTER=
  export SPLICE_APP_UI_NETWORK_NAME=""
  export SPLICE_APP_UI_NETWORK_FAVICON_URL=""
  export SPLICE_APP_UI_AMULET_NAME=""
  export SPLICE_APP_UI_AMULET_NAME_ACRONYM=""
  export SPLICE_APP_UI_NAME_SERVICE_NAME=""
  export SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM=""
  docker volume rm splice-validator_postgres-splice > /dev/null 2>&1 || true
  docker compose -f "${VALIDATOR_DIR}/compose.yaml" up -d postgres-splice
  _info "Waiting for postgres to be ready"
  # shellcheck disable=SC2034
  for i in {1..10}; do
    docker exec splice-validator-postgres-splice-1 pg_isready && break
    sleep 6
  done
  if ( ! docker exec splice-validator-postgres-splice-1 pg_isready ); then
    _error "Postgres is not ready after 1 minute"
  fi
  docker exec -i splice-validator-postgres-splice-1 psql -U cnadmin validator < "$validator_backup_file"
  docker exec -i splice-validator-postgres-splice-1 psql -U cnadmin participant-"$MIGRATION_ID" < "$participant_backup_file"
  docker compose -f "${VALIDATOR_DIR}/compose.yaml" down
}

subcommand_whitelist[identities_dump]='Fetch an identities dump from the validator'
function subcmd_identities_dump {

  if [ $# -lt 1 ]; then
    _error "Usage: $SCRIPTNAME identities_dump <output_file>"
  fi

  output_file=$1

  VALIDATOR_AUTH_AUDIENCE="$DEFAULT_AUDIENCE"
  export VALIDATOR_AUTH_AUDIENCE

  token=$("${VALIDATOR_DIR}/get-token.py" ledger-api-user)
  curl -sSLf 'http://wallet.localhost/api/validator/v0/admin/participant/identities' -H "authorization: Bearer $token" > "$output_file"
}



################################
### Main
################################

if [ $# -eq 0 ]; then
    subcmd_help

    _error  "Missing subcommand"
fi

SUBCOMMAND_NAME="$1"
shift

if [ ! ${subcommand_whitelist[${SUBCOMMAND_NAME}]+_} ]; then
    subcmd_help

    _error  "Unknown subcommand: ${SUBCOMMAND_NAME}"
fi

"subcmd_${SUBCOMMAND_NAME}" "$@"
