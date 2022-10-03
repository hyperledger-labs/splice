#!/usr/bin/env bash

if [ ! -f "envoy.pid" ]; then
  echo "The file envoy.pid does not exist, not stopping envoy"
  exit 1
fi

PID=$(cat envoy.pid)
kill "$PID"

rm envoy.pid
rm envoy-out.json
