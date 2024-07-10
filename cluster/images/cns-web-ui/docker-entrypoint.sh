#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

echo "Generating config.js file..."
# shellcheck disable=SC2016
envsubst '$CN_APP_CNS_UI_AUTH_CLIENT_ID,$CN_APP_CNS_UI_AUTH_URL,$CN_APP_CNS_UI_AUTH_AUDIENCE,$CN_APP_CNS_UI_CLUSTER' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
