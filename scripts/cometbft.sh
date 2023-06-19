#!/usr/bin/env bash

set -eou pipefail

DOCKER_COMETBFT_CONTAINER_NAME="cometbft-for-cn-node"

function docker_start() {
  echo "Starting Cometbft docker container"
  docker run -d --rm \
    --name "$DOCKER_COMETBFT_CONTAINER_NAME" \
    --entrypoint testing-entrypoint.sh \
    -p 26657:26657 \
    "$COMETBFT_DOCKER_IMAGE"
}

function docker_stop() {
  echo "Removing Cometbft docker container"
  docker stop "$DOCKER_COMETBFT_CONTAINER_NAME" || true
  docker rm $DOCKER_COMETBFT_CONTAINER_NAME || true
}

case "$1" in
    start)
        if [[ ! -v CI ]]; then
          docker_start
        else
          echo "Running in CI, not starting container."
        fi;
    ;;
    stop)
        if [[ ! -v CI ]]; then
          docker_stop
        else
          echo "Running in CI, no container to stop."
        fi;
    ;;
    *)
        echo "Usage: ./cometbft.sh COMMAND"
        echo ""
        echo "  COMMAND"
        echo "    start            makes sure the cometbft instance is running"
        echo "    stop             removes the cometbft instance along with all data"
    ;;
esac
