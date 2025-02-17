#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

echo "## Running daml tests in 'internal/' directory "
(cd "$REPO_ROOT" && ./internal/scripts/run-internal-daml-tests.sh)

echo "## Running daml tests in 'cn-token-standard-proposal/' directory "
# Run script in sub-shell that changed to `cn-token-standard-proposal` directory
(cd "$REPO_ROOT/cn-token-standard-proposal" && ./script/daml-test-all.sh)
