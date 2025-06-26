#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Checkout relevant git branches and tags
# Note that we need to checkout main and the current branch also because the todo checker uses
# `git log main..$BRANCH` so both main and the branch have to be local refs

current_commit=$(git rev-parse HEAD)

git config --global --add safe.directory "$(pwd)"
echo "FETCHING ORIGIN"
git fetch --tags --force origin
echo "CHECKING OUT MAIN"
git checkout main
echo "GITHUB_HEAD_REF: ${GITHUB_HEAD_REF:-}"
if [ -n "${GITHUB_HEAD_REF:-}" ] && [ "$GITHUB_HEAD_REF" != "main" ]; then
  echo "Checking out $GITHUB_HEAD_REF"
  # On PRs from forks, GITHUB_HEAD_REF is the name of the branch in the forked repo, so we cannot actually checkout that branch directly.
  git checkout "$GITHUB_HEAD_REF" || true
fi
git fetch origin 'refs/heads/release-line*:refs/heads/origin/release-line*' --force

git checkout "$current_commit"

# Compute open-api cache key, that's used in many different caches so we compute it once here.
find . -wholename '*/openapi/*.yaml' | LC_ALL=C sort | xargs sha256sum > openapi-cache-key.txt
