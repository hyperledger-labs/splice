#!/usr/bin/env bash
set -eou pipefail

jsonnet --tla-str hostname="127.0.0.1" envoy.jsonnet > envoy-config.json
if [ -z "$(which envoy)" ]; then
  echo "envoy executable not found. On MacOS, please install envoy globally using brew" >&2
  exit 1
fi
echo "Starting envoy..."
envoy --log-level debug --log-path ../log/envoy-system.log -c envoy-config.json > ../log/envoy-out.log 2>&1 &
PID=$!
echo "$PID" > envoy.pid
