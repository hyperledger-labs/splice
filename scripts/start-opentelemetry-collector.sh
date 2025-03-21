#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [[ -v CI ]]; then
  export ENVIRONMENT="ci"
  export BUILD_USER=${CIRCLE_USERNAME}
  export BUILD_BRANCH=${CIRCLE_BRANCH}
  export BUILD_NUM=${CIRCLE_BUILD_NUM}
  export BUILD_JOB="${CIRCLE_JOB}-${CIRCLE_NODE_INDEX:-0}"
else
  export ENVIRONMENT="local"
  user=$(whoami)
  export BUILD_USER=$user
  branch=$(git rev-parse --abbrev-ref HEAD)
  export BUILD_BRANCH=$branch
  export BUILD_NUM="0"
  export BUILD_JOB="local"
fi;

mkdir -p "$LOGS_PATH"
compose_file="$SPLICE_ROOT"/build-tools/observability/opentelemetry-collector.yml
docker compose -f "${compose_file}" create
docker compose -f "${compose_file}" cp "${SPLICE_ROOT}"/build-tools/observability/otel-collector-config.yaml otel-collector:/etc/otel-collector-config.yaml
JWT=$(mktemp)
trap 'rm -rf $JWT' EXIT
# For some reason `docker compose cp` does not like a <() substitution so we use a temp file.
echo -n "$PROMETHEUS_REMOTE_WRITE_JWT" > "$JWT"
chmod 755 "$JWT"
docker compose -f "${compose_file}" cp "$JWT" otel-collector:/etc/file-containing.token
docker compose -f "${compose_file}" start
docker compose -f "${compose_file}" logs -f > "$LOGS_PATH"/otel_collector.log &
