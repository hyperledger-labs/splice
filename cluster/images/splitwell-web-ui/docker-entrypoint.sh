#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

echo "Generating config.js file..."
# shellcheck disable=SC2016
envsubst '$SPLICE_APP_UI_AUTH_CLIENT_ID,$SPLICE_APP_UI_AUTH_URL,$SPLICE_APP_UI_AUTH_AUDIENCE,$SPLICE_APP_UI_CLUSTER,$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
