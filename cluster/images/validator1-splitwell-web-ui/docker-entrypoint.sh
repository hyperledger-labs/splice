#!/usr/bin/env bash
set -eou pipefail

echo "Generating config.js file..."
cat /tmpl/config.js.tmpl | envsubst '$CN_APP_SPLITWELL_UI_AUTH_CLIENT_ID,$CN_APP_SPLITWELL_UI_AUTH_URL' > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
