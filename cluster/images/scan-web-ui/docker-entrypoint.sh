#!/usr/bin/env bash
set -eou pipefail

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
