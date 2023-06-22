#!/usr/bin/env bash

set -eou pipefail

DOCKER_COMETBFT_CONTAINER_NAME="cometbft-for-cn-node"
REPO_ROOT=$( git rev-parse --show-toplevel )
DOCKER_COMEBFT_NETWORK_NAME="cometbft-for-cn-node"

function create_docker_network() {
  docker network create $DOCKER_COMEBFT_NETWORK_NAME
}

function delete_docker_network() {
  docker network rm $DOCKER_COMEBFT_NETWORK_NAME || true
}

function docker_start() {
  local container_sv_index=$1
  local current_container_name="${DOCKER_COMETBFT_CONTAINER_NAME}_${container_sv_index}"
  echo "Starting Cometbft docker container $current_container_name"
  docker create \
    --name "$current_container_name" \
    --volume /cometbft/config \
    --volume /cometbft/data \
    --rm \
    --expose 26656 \
    --expose 26657 \
    -p "266${1}7:26657" \
    --network $DOCKER_COMEBFT_NETWORK_NAME \
    --hostname "sv${container_sv_index}" \
    "$COMETBFT_DOCKER_IMAGE" start --home /cometbft
  local cometbft_config_path="$REPO_ROOT/apps/sv/src/test/resources/cometbft"
  docker cp "${cometbft_config_path}/sv1/config/genesis.json" "$current_container_name:/cometbft/config/genesis.json"
  docker cp "${cometbft_config_path}/sv${container_sv_index}/config" "$current_container_name:/cometbft/"
  docker cp "${cometbft_config_path}/sv${container_sv_index}/data" "$current_container_name:/cometbft/"
  docker start "$current_container_name"
  mkdir -p "$REPO_ROOT/log"
  docker logs -f "$current_container_name" > "$REPO_ROOT/log/$current_container_name.log" &
}

function docker_stop() {
  local container_sv_index=$1
  local current_container_name="${DOCKER_COMETBFT_CONTAINER_NAME}_${container_sv_index}"
  echo "Removing Cometbft docker container $current_container_name"
  docker stop "$current_container_name" || true
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
        docker ps -a
    ;;
    stop)
        docker_stop 1
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
