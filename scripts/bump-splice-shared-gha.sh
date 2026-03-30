#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

submodule_name="splice-shared-gha"

git submodule update --init --force --remote -- "$submodule_name"

if git diff --exit-code --quiet $submodule_name; then
  echo "$submodule_name version is up to date."
  exit 0
fi

git add -u
echo "$submodule_name version is not up to date. Creating a PR..."

git add "$submodule_name"
updated_branch="$submodule_name-bump-$(date +%Y-%m)"
git switch -c "${updated_branch}"
git commit -m "[ci] bump $submodule_name version to the latest (auto-generated)" -s
git push origin "${updated_branch}" --force

pr_state=$(gh pr view "$updated_branch" --json state --jq '.state' 2>/dev/null || echo "NOT_FOUND")
if [ "$pr_state" == "OPEN" ]; then
  echo "PR already open. Nothing more to do."
else
  gh pr create \
    --base "main" \
    --head "$updated_branch" \
    --title "Bump $submodule_name version to the latest (auto-generated)" \
    --body "" \
    --reviewer martinflorian-da,krzysztofczyz-da,mblaze-da
fi
echo "Done."
