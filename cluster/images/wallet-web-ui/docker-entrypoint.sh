#!/usr/bin/env bash
set -eou pipefail

echo "Generating config.js file..."
cat /tmpl/config.js.tmpl | envsubst '$CN_APP_WALLET_UI_AUTH_CLIENT_ID' > /usr/share/nginx/html/config.js

echo "Starting nginx"
cat /docker-entrypoint.sh
exec /docker-entrypoint.sh nginx -g 'daemon off;'
