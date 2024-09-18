#!/bin/usr/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

echo "Starting k6"
k6 \
    --verbose \
    --out experimental-prometheus-rw \
    --env EXTERNAL_CONFIG="$EXTERNAL_CONFIG" \
    --log-format json \
    run "generate-load.js"
