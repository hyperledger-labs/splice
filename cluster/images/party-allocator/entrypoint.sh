#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC2016
EXTERNAL_CONFIG="$(echo "$EXTERNAL_CONFIG" | envsubst '$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME')"
export EXTERNAL_CONFIG

exec node --enable-source-maps party-allocator/bundle.js
