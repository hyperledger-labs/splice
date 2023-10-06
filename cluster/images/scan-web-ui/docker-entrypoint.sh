#!/usr/bin/env bash
set -eou pipefail

echo "Generating config.js file..."
# shellcheck disable=SC2016
envsubst '$CN_APP_DIRECTORY_UI_CLUSTER' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
