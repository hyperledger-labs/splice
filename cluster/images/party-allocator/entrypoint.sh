#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

export EXTERNAL_CONFIG="{\"token\": \"${SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN}\", \"jsonLedgerApiUrl\": \"$JSON_LEDGER_API_URL\", \"scanApiUrl\": \"$SCAN_API_URL\", \"validatorApiUrl\": \"$VALIDATOR_API_URL\", \"maxParties\": $MAX_PARTIES, \"keyDirectory\": \"$KEYS_DIRECTORY\", \"parallelism\": $PARALLELISM}"

exec node --enable-source-maps party-allocator/bundle.js
