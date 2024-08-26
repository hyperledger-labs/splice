#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

echo "Generating config.js file..."
# shellcheck disable=SC2016
envsubst '$CN_APP_UI_AUTH_CLIENT_ID,$CN_APP_UI_AUTH_URL,$CN_APP_UI_AUTH_AUDIENCE,$CN_APP_UI_NETWORK_NAME,$CN_APP_UI_AMULET_NAME,$CN_APP_UI_AMULET_NAME_ACRONYM,$CN_APP_UI_NAME_SERVICE_NAME,$CN_APP_UI_NAME_SERVICE_NAME_ACRONYM,$CN_APP_UI_NETWORK_FAVICON_URL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
