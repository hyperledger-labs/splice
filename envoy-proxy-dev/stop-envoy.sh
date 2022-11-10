#!/usr/bin/env bash
set -eou pipefail

if [ ! -f "envoy.pid" ]; then
  echo "The file envoy.pid does not exist, not stopping envoy"
else
  PID=$(cat envoy.pid)
  echo "Stopping envoy..."
  kill "$PID" || true
  rm envoy.pid
fi

# remove, even if the envoy process was stopped another way already
echo "Removing envoy-config.json if it exists"
rm -f envoy-config.json
