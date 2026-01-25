#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ -z "${SPLICE_CLUSTER-}" ]; then
  echo "SPLICE_CLUSTER is not set"
  exit 1
fi

if [ "${SPLICE_CLUSTER}" == "main" ]; then
  export SPLICE_URL_PREFIX=""
else
  export SPLICE_URL_PREFIX="${SPLICE_CLUSTER}."
fi

# shellcheck disable=SC2016
envsubst '$SPLICE_CLUSTER,$SPLICE_URL_PREFIX' < /tmpl/script.js.tmpl > /usr/share/nginx/html/_static/script.js

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
