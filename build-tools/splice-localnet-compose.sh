#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Ensure all background jobs are killed when the script exits
trap 'kill $(jobs -p) 2>/dev/null' EXIT
exec > >(tee -a "${SPLICE_ROOT}/log/console.log") 2>&1

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
SCRIPTNAME=${0##*/}

LOCALNET_DIR="${SPLICE_ROOT}/cluster/compose/localnet"
export LOCALNET_DIR

IMAGE_TAG=$("${SPLICE_ROOT}/build-tools/get-snapshot-version")
export IMAGE_TAG
IMAGE_REPO=""
export IMAGE_REPO

# let docker assign a port to postgres. In CI, we have another postgres instance running, so can't use the default 5432
DB_PORT=""
export DB_PORT

ACTION=""
MULTI_SYNC_PROFILE=()
DOWN_COMMAND=( stop )
ALPHA_PROTOCOL_VERSION_ENV=""

function usage() {
    echo "Usage: $SCRIPTNAME <start|stop> [-D] [-M] [-u] [-p <protocol_version>]"
    echo ""
    echo "Options:"
    echo "  -D                        Completely tear down the localnet (using 'docker compose down') instead of just stopping the containers (using 'docker compose stop')"
    echo "  -M                        Start the localnet with the 'multi-sync' profile enabled"
    echo "  -u                        Enable unstable Canton protocol versions. WARNING: This should be used only for temporary test environments that be be reset often."
    echo "  -p <protocol_version>     Set the PROTOCOL_VERSION environment variable to the specified value (e.g. 35)"
}

if [[ $# -lt 1 ]]; then
    usage
    exit 1
fi

case $1 in
    start|stop)
        ACTION=$1
        ;;
    *)
        echo "Invalid action: $1. Use 'start' or 'stop'."
        usage
        exit 1
        ;;
esac
shift

while [[ $# -gt 0 ]]; do
    case $1 in
        -D)
            DOWN_COMMAND=( down -v )
            ;;
        -M)
            MULTI_SYNC_PROFILE=( --profile multi-sync )
            ;;
        -p)
            shift
            if [[ -z "$1" ]]; then
                echo "Error: -P requires a protocol version argument."
                usage
                exit 1
            fi
            CANTON_PROTOCOL_VERSION=$1
            export CANTON_PROTOCOL_VERSION
            ;;
        -u)
            ALPHA_PROTOCOL_VERSION_ENV=$LOCALNET_DIR/env/alpha-protocol-version.env
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
    shift
done

export ALPHA_PROTOCOL_VERSION_ENV

DOCKER_COMPOSE_CMD=( docker compose
    --env-file "$LOCALNET_DIR/compose.env"
    --env-file "$LOCALNET_DIR/env/common.env"
    -f "$LOCALNET_DIR/compose.yaml"
    -f "$LOCALNET_DIR/resource-constraints.yaml"
    --profile sv
    --profile app-provider
    --profile app-user
)

case $ACTION in
    start)
        services_to_log=( canton splice postgres nginx )
        docker system events -f type=container \
                      -f event=start \
                      -f event=stop \
                      -f event=restart \
                      -f event=kill \
                      -f event=die \
                      -f event=destroy \
                      -f event=health_status \
                      -f event=oom \
                      --format '{{.Actor.ID}} {{.Time}} {{.Actor.Attributes.name}} {{.Action}}' | while read -r cid time service_name status; do
          if [[ -n "$service_name" ]]; then
              for svc in "${services_to_log[@]}"; do
                  if [[ "$svc" == "$service_name" ]]; then
                      echo "$(date -u -d "@$time" +"%Y-%m-%dT%H:%M:%S") $service_name $status"
                      if [ "$status" = "start" ]; then
                          echo " capture logs $service_name"
                          docker logs -f "$cid" >> "${SPLICE_ROOT}/log/compose-localnet-$service_name.clog" 2>&1 &
                      fi
                      break
                  fi
              done
          fi
        done >> "${SPLICE_ROOT}/log/compose.log" 2>&1 &
        "${DOCKER_COMPOSE_CMD[@]}" "${MULTI_SYNC_PROFILE[@]}" up -d || _error "Failed to start localnet, please check ${SPLICE_ROOT}/log/console.log for details"
        ;;
    stop)
        "${DOCKER_COMPOSE_CMD[@]}" "${MULTI_SYNC_PROFILE[@]}" "${DOWN_COMMAND[@]}"
        ;;
esac
