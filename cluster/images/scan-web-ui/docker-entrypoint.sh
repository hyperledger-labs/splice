#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

http=${SPLICE_APP_UI_HTTP_URL:-false}

echo "Generating config.js file..."

if [ "$http" == "true" ] || [ "$http" == "1" ]; then
  echo "WARNING: Using an http url"
  sed -i 's/url: "https:\/\/"/url: "http:\/\/"/' /tmpl/config.js.tmpl
fi

# shellcheck disable=SC2016
envsubst '$CN_APP_UI_NETWORK_NAME,$CN_APP_UI_AMULET_NAME,$CN_APP_UI_AMULET_NAME_ACRONYM,$CN_APP_UI_NAME_SERVICE_NAME,$CN_APP_UI_NAME_SERVICE_NAME_ACRONYM,$CN_APP_UI_NETWORK_FAVICON_URL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
