#!/usr/bin/env bash

if [ ! -f "envoy.pid" ]; then
  echo "The file envoy.pid does not exist, not stopping envoy"
  exit 1
fi

PID=$(cat envoy.pid)
if [ "$PID" == "docker" ]; then
  docker stop envoy
else
  kill "$PID"
fi

rm envoy.pid
rm envoy-out.json
