#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

http=${SPLICE_APP_UI_HTTP_URL:-false}

echo "Generating config.js file..."

if [ "$http" == "true" ] || [ "$http" == "1" ]; then
  echo "WARNING: Using an http url"
  sed -i 's/url: "https:\/\/"/url: "http:\/\/"/' /tmpl/config.js.tmpl
fi

# shellcheck disable=SC2016
envsubst '$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL,$SPLICE_APP_UI_POLL_INTERVAL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
