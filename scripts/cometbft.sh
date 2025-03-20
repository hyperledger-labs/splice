#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

DOCKER_COMETBFT_CONTAINER_NAME="cometbft-for-splice-node"
DOCKER_COMEBFT_NETWORK_NAME="cometbft-for-splice-node"

function create_docker_network() {
  docker network create $DOCKER_COMEBFT_NETWORK_NAME
}

function delete_docker_network() {
  if docker network ls --format '{{.Name}}' | grep -q "^$DOCKER_COMEBFT_NETWORK_NAME$"; then
    docker network rm $DOCKER_COMEBFT_NETWORK_NAME  || true
  else
    echo "No CometBFT docker network '$DOCKER_COMEBFT_NETWORK_NAME' to remove."
  fi
}

function docker_start() {
  local container_sv_index=$1

  local port_offset
  if [[ $container_sv_index == "2Local" ]]; then
    port_offset=5
  else
    port_offset=$container_sv_index
  fi

  local current_container_name="${DOCKER_COMETBFT_CONTAINER_NAME}_${container_sv_index}"
  echo "Starting Cometbft docker container $current_container_name"
  docker create \
    --name "$current_container_name" \
    --volume /cometbft/config \
    --volume /cometbft/data \
    --rm \
    --expose 26656 \
    --expose 26657 \
    -p "266${port_offset}7:26657" \
    -p "266${port_offset}0:26660" \
    --network $DOCKER_COMEBFT_NETWORK_NAME \
    --hostname "sv${container_sv_index}" \
    "$COMETBFT_DOCKER_IMAGE" start --home /cometbft
  local cometbft_config_path="$SPLICE_ROOT/apps/sv/src/test/resources/cometbft"
  # The network must be started with all the nodes as validators to ensure stability
  # This is done to ensure it's an actual BFT network
  # We don't ensure this by enabling the reconciliation loops because it would destabilize the network
  # by adding/removing nodes from the validator set depending on what SV apps we start
  docker cp "${cometbft_config_path}/genesis-multi-validator.json" "$current_container_name:/cometbft/config/genesis.json"
  docker cp "${cometbft_config_path}/sv${container_sv_index}/config" "$current_container_name:/cometbft/"
  docker cp "${cometbft_config_path}/sv${container_sv_index}/data" "$current_container_name:/cometbft/"
  docker start "$current_container_name"
  # For some reason even if we create this from direnv, in CI it does not exist
  mkdir -p "$LOGS_PATH"
  docker logs -f "$current_container_name" > "$LOGS_PATH/$current_container_name.log" &
}

function docker_stop() {
  local container_sv_index=$1
  local current_container_name="${DOCKER_COMETBFT_CONTAINER_NAME}_${container_sv_index}"
  if docker ps -a --format '{{.Names}}' | grep -q "^$current_container_name$"; then
    echo "Removing Cometbft docker container $current_container_name."
    docker stop "$current_container_name" || true
  else
    echo "No CometBFT $current_container_name container to remove."
  fi
}

case "$1" in
    start)
        if [[ -v CI ]]; then
          # In circleCI the gateway address represents the remote docker host address
          # This allows us to access containers that have host binded ports
          # Same mechanism that testcontainers uses https://github.com/testcontainers/testcontainers-java/blob/main/core/src/main/java/org/testcontainers/dockerclient/DockerClientProviderStrategy.java?rgh-link-date=2023-06-22T06%3A45%3A46Z#L450
          cometbft_ip="$(docker network inspect bridge -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}')"
          export COMETBFT_DOCKER_IP="$cometbft_ip"
        else
          export COMETBFT_DOCKER_IP="localhost"
        fi;
        create_docker_network
        docker_start 1
        docker_start 2
        docker_start 3
        docker_start 4
        docker_start 2Local
        docker ps -a
    ;;
    stop)
        docker_stop 1
        docker_stop 2
        docker_stop 3
        docker_stop 4
        docker_stop 2Local
        delete_docker_network
    ;;
    *)
        echo "Usage: ./cometbft.sh COMMAND"
        echo ""
        echo "  COMMAND"
        echo "    start            makes sure the cometbft instance is running"
        echo "    stop             removes the cometbft instance along with all data"
    ;;
esac
