#!/usr/bin/env bash

jsonnet envoy.jsonnet > envoy-out.json

docker run -d --rm --name envoy --add-host=host.docker.internal:host-gateway -v $PWD:/conf -it \
    -p 9901:9901 \
    -p 6204:6204 -p 6304:6304 \
    -p 8082:8082 -p 8083:8083 -p 8084:8084 -p 8085:8085 -p 8086:8086 \
    envoyproxy/envoy:v1.23-latest \
    -c /conf/envoy-out.json
