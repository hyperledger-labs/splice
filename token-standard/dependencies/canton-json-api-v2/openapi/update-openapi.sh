#!/bin/sh

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

echo "This script assumes that you started canton with start-canton.sh -w"
echo "and will fetch the openapi definition from there."
echo "This should run on the Canton version AFTER updating."

OPENAPI_DIR="${SPLICE_ROOT}/token-standard/dependencies/canton-json-api-v2/openapi"
TARGET_FILE="openapi.yaml"
PATCH_FILE="${OPENAPI_DIR}/openapi.patch"

if [ ! -f "$PATCH_FILE" ]; then
  echo "$PATCH_FILE does not exist"
  exit 1
fi

echo "Fetching..."
curl -sSLf 'http://localhost:6201/docs/openapi' > "$TARGET_FILE"

echo "Applying patch..."
patch -R -p0 < "$PATCH_FILE"
