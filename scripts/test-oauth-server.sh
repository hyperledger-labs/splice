#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$SPLICE_ROOT"
cd ./scripts/test-oauth-server/

node ./src/index.mjs "$@"
