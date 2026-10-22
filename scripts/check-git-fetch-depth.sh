#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Fails on git fetches that download full history (#7831), i.e. in CI workflows and scripts:
# - `fetch-depth: 0` in .github
# - `git fetch` / `git clone` without --depth, --deepen, --shallow-* or --filter
# A finding is allowed if the line or the line above contains `# full-history-ok: <reason>`.
# Calls through wrapper functions are not checked; annotate the wrapper's own `git fetch`.

cd "$(git rev-parse --show-toplevel)"

files=()
while IFS= read -r file; do
  # skips submodules
  if [ -f "$file" ]; then files+=("$file"); fi
done < <(git ls-files -- .github scripts build-tools '*.sh' '*.mk' 'Makefile' '**/Makefile' \
  ':!canton/*' ':!*.md' ':!*.mdx' ':!*.rst' ':!*.scala' ':!scripts/check-git-fetch-depth.sh')

findings=$(awk '
  FNR == 1 { prev = "" }
  {
    line = $0
    allowed = (line ~ /# full-history-ok:/) || (prev ~ /# full-history-ok:/)
    prev = line
    if (allowed || line ~ /^[[:space:]]*#/) next
    if (FILENAME ~ /^\.github\// && line ~ /fetch-depth:[[:space:]]*0([^0-9]|$)/) {
      print FILENAME ":" FNR ": fetch-depth: 0: " line
    } else if (line ~ /git[[:space:]]+(fetch|clone)([^a-z_-]|$)/ && line !~ /--(depth|deepen|shallow-|filter)/) {
      print FILENAME ":" FNR ": unbounded git fetch/clone: " line
    }
  }
' "${files[@]}")

if [ -n "$findings" ]; then
  echo "ERROR: found git fetches that download full history:"
  echo "$findings"
  echo "Bound them with --depth, --deepen, --shallow-* or --filter=blob:none, or annotate the line"
  echo "or the line above with '# full-history-ok: <reason>'. See \"Git history in CI\" in TESTING.md."
  exit 1
else
  echo "No unbounded git fetches found."
fi
