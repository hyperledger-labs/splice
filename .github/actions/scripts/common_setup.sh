#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Checkout relevant git branches and tags
# Note that we need to checkout main and the current branch also because the todo checker uses
# `git log main..$BRANCH` so both main and the branch have to be local refs

git config --global --add safe.directory "$(pwd)"
echo "FETCHING ORIGIN"
git fetch --tags --force origin
echo "CHECKING OUT MAIN"
git checkout main
echo "GITHUB_HEAD_REF: ${GITHUB_HEAD_REF:-}"
if [ -n "${GITHUB_HEAD_REF:-}" ] && [ "$GITHUB_HEAD_REF" != "main" ]; then
  echo "Checking out $GITHUB_HEAD_REF"
  git checkout "$GITHUB_HEAD_REF"
fi
git fetch origin 'refs/heads/release-line*:refs/heads/origin/release-line*' --force

echo "GITHUB_SHA: ${GITHUB_SHA:-}"
if [ -n "${GITHUB_SHA:-}" ]; then
  echo "Checking out $GITHUB_SHA"
  git fetch origin "$GITHUB_SHA" --force
  git checkout "$GITHUB_SHA"
fi

# Compute open-api cache key, that's used in many different caches so we compute it once here.
find . -wholename '*/openapi/*.yaml' | LC_ALL=C sort | xargs sha256sum > openapi-cache-key.txt
