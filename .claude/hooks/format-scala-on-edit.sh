#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# PostToolUse hook: mirrors the `scalafmt` pre-commit hook but runs immediately
# after Edit/Write instead of waiting for commit. Requires a running sbt
# thin-client server; silently no-ops if one isn't up so it never blocks edits.
set -uo pipefail

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')

case "$file" in
  *.scala)
    cd "$CLAUDE_PROJECT_DIR" && timeout 60 sbt --client "scalafmtOnly $file" >/dev/null 2>&1 || true
    ;;
esac

exit 0
