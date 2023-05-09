#!/usr/bin/env bash
set -eou pipefail

echo "Generating config.js file..."
# shellcheck disable=SC2016
envsubst '$CN_APP_SV_UI_AUTH_CLIENT_ID,$CN_APP_SV_UI_AUTH_URL,$CN_APP_SV_UI_AUTH_AUDIENCE,$CN_APP_SV_UI_CLUSTER' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
