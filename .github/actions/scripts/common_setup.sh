#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC2034
start_time=$SECONDS
trap 'echo "total elapsed: $((SECONDS - start_time))s"' EXIT

# Checkout relevant git branches and tags
# Note that we need to checkout main and the current branch also because the todo checker uses
# `git log main..$BRANCH` so both main and the branch have to be local refs

current_commit=$(git rev-parse HEAD)

FETCH_TIMEOUT_SECONDS=${FETCH_TIMEOUT_SECONDS:-300}
git_fetch() {
  timeout --kill-after=30 "$FETCH_TIMEOUT_SECONDS" git fetch "$@"
}

git config --global --add safe.directory "$(pwd)"
echo "FETCHING ORIGIN MAIN"
echo "GITHUB_HEAD_REF: ${GITHUB_HEAD_REF:-}"
refspecs=('refs/heads/main:refs/remotes/origin/main')
if [ -n "${GITHUB_HEAD_REF:-}" ] && [ "$GITHUB_HEAD_REF" != "main" ]; then
  refspecs+=("refs/heads/${GITHUB_HEAD_REF}:refs/remotes/origin/${GITHUB_HEAD_REF}")
fi
for attempt in {1..3}; do
  # On PRs from forks, GITHUB_HEAD_REF is the name of the branch in the forked repo, so we cannot actually fetch that branch directly.
  if git_fetch --no-tags --force origin "${refspecs[@]}" || git_fetch --no-tags --force origin 'refs/heads/main:refs/remotes/origin/main'; then
    break
  else
    echo "Fetch attempt $attempt failed. Retrying..."
    if [ "$attempt" -eq 3 ]; then
      echo "This was the last attempt. Exiting."
      exit 1
    fi
    sleep 5
  fi
done
echo "CHECKING OUT MAIN"
git checkout main
if [ -n "${GITHUB_HEAD_REF:-}" ] && [ "$GITHUB_HEAD_REF" != "main" ]; then
  echo "Checking out $GITHUB_HEAD_REF"
  # On PRs from forks, GITHUB_HEAD_REF is the name of the branch in the forked repo, so we cannot actually checkout that branch directly.
  git checkout "$GITHUB_HEAD_REF" || true
fi
echo "FETCHING LATEST RELEASE LINE"
latest_release=$(cat LATEST_RELEASE)
for attempt in {1..3}; do
  if git_fetch origin "refs/heads/release-line-${latest_release}:refs/remotes/origin/release-line-${latest_release}" --force; then
    break
  else
    echo "Fetch attempt $attempt for release line ${latest_release} failed. Retrying..."
    if [ "$attempt" -eq 3 ]; then
      echo "This was the last attempt. Exiting."
      exit 1
    fi
    sleep 5
  fi
done

git checkout "$current_commit"

# Compute open-api cache key, that's used in many different caches so we compute it once here.
find . -wholename '*/openapi/*.yaml' | LC_ALL=C sort | xargs sha256sum > openapi-cache-key.txt
