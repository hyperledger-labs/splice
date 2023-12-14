#!/bin/usr/env bash
set -euo pipefail

echo "Starting k6"
k6 \
    --verbose \
    --out experimental-prometheus-rw \
    --env EXTERNAL_CONFIG="$EXTERNAL_CONFIG" \
    run "generate-load.js"
