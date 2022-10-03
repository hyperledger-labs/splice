#!/usr/bin/env bash
set -eou pipefail

jsonnet --tla-str hostname="127.0.0.1" envoy.jsonnet > envoy-out.json
if [ -z "$(which envoy)" ]; then
  echo "envoy executable not found. On MacOS, please install envoy globally using brew" >&2
  exit 1
fi
envoy -c envoy-out.json &
PID=$!
echo "$PID" > envoy.pid
