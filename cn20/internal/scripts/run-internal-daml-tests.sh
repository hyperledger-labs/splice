#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Change to the repo root
cd "$REPO_ROOT"

# TODO (cn20#586): multi-package support for daml test
# Until we have multi-package test support we prefer to run our test Dars rather than use 'daml test' to
# interpret each Daml package's test source code
find internal/examples -name "*-test*\.dar" | while read -r testDar
do
  echo "Running tests in $testDar"
  daml script --dar "$testDar" --all --ide-ledger --static-time
done

