#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# PostToolUse hook: mirrors the `typescriptfmt` pre-commit hook (scripts/fix-ts.py)
# but runs immediately after Edit/Write instead of waiting for commit.
set -uo pipefail

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')

case "$file" in
  *.prettierrc.cjs)
    ;;
  *.ts|*.tsx|*.js|*.jsx)
    cd "$CLAUDE_PROJECT_DIR" && scripts/fix-ts.py "$file" >/dev/null 2>&1 || true
    ;;
esac

exit 0
