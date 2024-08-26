#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

docker_gateway=$(docker network inspect bridge -f "{{range .IPAM.Config}}{{.Gateway}}{{end}}")

secret=$(curl -sSfL -X POST "http://127.0.0.1:5114/api/sv/v0/devnet/onboard/validator/prepare")

IMAGE_REPO=""
export IMAGE_REPO
IMAGE_TAG=$("${REPO_ROOT}/build-tools/get-snapshot-version")
export IMAGE_TAG

"${REPO_ROOT}/cluster/deployment/compose/start.sh" \
  -s "http://${docker_gateway}:5114" \
  -c "http://${docker_gateway}:5012" \
  -q "http://${docker_gateway}:5108" \
  -o "$secret" \
  -b >> "${REPO_ROOT}/log/compose.log" 2>&1

for c in validator participant; do
  docker logs -f compose-${c}-1 >> "${REPO_ROOT}/log/compose-${c}.clog" 2>&1 &
done
