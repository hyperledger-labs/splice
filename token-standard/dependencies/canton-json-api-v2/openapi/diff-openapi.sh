#!/bin/sh

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

echo "This script assumes that you started canton with start-canton.sh -w"
echo "and will fetch the openapi definition from there."
echo "This should run on the Canton version BEFORE updating."

OPENAPI_DIR="${SPLICE_ROOT}/token-standard/dependencies/canton-json-api-v2/openapi"
TARGET_FILE="openapi.yaml"

TEMP_DIR=$(mktemp -d)
TEMP_FILE="${TEMP_DIR}/openapi.yaml"

echo "Fetching..."
curl -sSLf 'http://localhost:6201/docs/openapi' > "$TEMP_FILE"

PATCH_FILE="${OPENAPI_DIR}/openapi.patch"
echo "Making a patch file to ${PATCH_FILE}"
diff -u "$TARGET_FILE" "$TEMP_FILE" > "$PATCH_FILE"
