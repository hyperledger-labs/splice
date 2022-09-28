#!/usr/bin/env bash

mode=${1:-docker}


case "$mode" in
  docker)
    jsonnet envoy.jsonnet > envoy-out.json
    docker run -d --rm --name envoy --add-host=host.docker.internal:host-gateway -v $PWD:/conf -it \
        -p 9901:9901 \
        -p 6204:6204 -p 6304:6304 \
        -p 8082:8082 -p 8083:8083 -p 8084:8084 -p 8085:8085 -p 8086:8086 \
        envoyproxy/envoy:v1.23-latest \
        -c /conf/envoy-out.json
    echo "docker" > envoy.pid
    ;;
  local)
    jsonnet --tla-str hostname="localhost" envoy.jsonnet > envoy-out.json
    envoy -c envoy-out.json &
    PID=$!
    echo "$PID" > envoy.pid
    ;;
  *)
    echo "Usage: ./start-envoy.sh <mode>"
    echo ""
    echo "modes:"
    echo "  docker    run envoy in a docker container"
    echo "  local     run envoy locally (currently supported only for linux)"
    exit 1
    ;;
esac
