#!/usr/bin/env bash

docker run --rm --name envoy --add-host=host.docker.internal:host-gateway -v $PWD:/conf -it \
       -p 9901:9901 \
       -p 6204:6204 -p 6304:6304 \
       envoyproxy/envoy:v1.23-latest \
       -c /conf/envoy.yaml
