#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

unsafe=${SPLICE_APP_UI_UNSAFE:-false}
http=${SPLICE_APP_UI_HTTP_URL:-false}

echo "Generating config.js file..."

if [ "$http" == "true" ] || [ "$http" == "1" ]; then
  echo "WARNING: Using an http url"
  sed -i 's/url: "https:\/\/"/url: "http:\/\/"/' /tmpl/config.js.tmpl
fi

if [ "$unsafe" == "true" ] || [ "$unsafe" == "1" ]; then
  echo "WARNING: Running in unsafe mode"
  sed -i 's/algorithm: "rs-256"/algorithm: "hs-256-unsafe"/' /tmpl/config.js.tmpl
  # shellcheck disable=SC2016
  sed -i 's/client_id: .*/secret: "${SPLICE_APP_UI_UNSAFE_SECRET}",/' /tmpl/config.js.tmpl
  sed -i 's/authority: .*//' /tmpl/config.js.tmpl
  # shellcheck disable=SC2016
  envsubst '$SPLICE_APP_UI_AUTH_AUDIENCE,$SPLICE_APP_UI_UNSAFE_SECRET,$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL,$SPLICE_APP_UI_POLL_INTERVAL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js
else
  # shellcheck disable=SC2016
  envsubst '$SPLICE_APP_UI_AUTH_CLIENT_ID,$SPLICE_APP_UI_AUTH_URL,$SPLICE_APP_UI_AUTH_AUDIENCE,$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL,$SPLICE_APP_UI_POLL_INTERVAL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js
fi

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
