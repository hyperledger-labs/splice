#!/usr/bin/env bash

docker run --rm --name envoy-splitwise --add-host=host.docker.internal:host-gateway -v $PWD:/conf -it \
       -p 9902:9901 \
       -p 8082:8082 -p 8083:8083 -p 8084:8084 \
       envoyproxy/envoy:v1.23-latest \
       -c /conf/envoy.yaml
