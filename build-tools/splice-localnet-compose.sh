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

# the port will be assigned by docker
TEST_PORT=""
export TEST_PORT

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $SCRIPTNAME <start|stop> [-D]"
    exit 1
fi

ACTION=$1

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
        if [[ $# -ne 1 ]]; then
            echo "Usage: $SCRIPTNAME <start|stop> [-D]"
            exit 1
        fi
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

        "${DOCKER_COMPOSE_CMD[@]}" up -d || _error "Failed to start localnet, please check ${SPLICE_ROOT}/log/console.log for details"
        ;;
    stop)
        if [[ $# -eq 2 && $2 == "-D" ]]; then
            "${DOCKER_COMPOSE_CMD[@]}" down -v
        elif [[ $# -eq 1 ]]; then
            "${DOCKER_COMPOSE_CMD[@]}" stop
        else
            echo "Usage: $SCRIPTNAME <start|stop> [-D]"
            exit 1
        fi
        ;;
    *)
        echo "Invalid action: $ACTION. Use 'start' or 'stop'."
        exit 1
        ;;
esac
