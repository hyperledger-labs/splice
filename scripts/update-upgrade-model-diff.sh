#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$SPLICE_ROOT"

TARGET_DIR=daml/upgrade-diffs

if [ "$#" -ge 1 ]; then
    TARGET_DIR="$1"
fi

diff_project() {
  local project_name="$1"
  # gnu diff includes dumb timestamps in the diff result so we `sed` them away.
  # this is great.
  diff -ur -x '*~' -x target -x dar -x .daml "daml/$project_name" "daml/$project_name-upgrade" | sed -E 's/^((---|\+\+\+) [^[:space:]]+).*/\1/g' > "$TARGET_DIR/$project_name.diff" || true
}

readarray -t projects < <(./scripts/lib/get-upgraded-daml-projects.sh)

for project in "${projects[@]}"; do
    diff_project "$project"
done
