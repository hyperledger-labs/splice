#!/usr/bin/env bash
set -eou pipefail

if [ ! -f "envoy.pid" ]; then
  echo "The file envoy.pid does not exist, not stopping envoy"
else
  PID=$(cat envoy.pid)
  kill "$PID"
  rm envoy.pid
fi

# remove, even if the envoy process was stopped another way already
echo "Deleting envoy-out.json"
rm -f envoy-out.json



