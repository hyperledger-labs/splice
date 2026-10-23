#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC2034
start_time=$SECONDS
trap 'echo "total elapsed: $((SECONDS - start_time))s"' EXIT

# FETCH_GIT_REFS selects which refs beyond the checked-out commit are fetched (#7831):
#   none     no extra fetches (default)
#   shallow  main and release-line-$LATEST_RELEASE tips, at depth 1 and without blobs; missing blobs
#            are fetched on demand. (A plain --depth=1 fetch makes git 2.43 resend the whole tree.)
#   history  main, the PR head branch and release-line-$LATEST_RELEASE with their full
#            commit history but no blobs (--filter=blob:none); missing blobs are fetched on demand.
#            The todo checker needs this, because it uses `git log main..$BRANCH`.
# For shallow and history, local branches main and $GITHUB_HEAD_REF are created if missing.
FETCH_GIT_REFS=${FETCH_GIT_REFS:-none}
case "$FETCH_GIT_REFS" in
  none) depth_args=() ;;
  shallow) depth_args=(--depth=1 --filter=blob:none) ;;
  history) depth_args=(--filter=blob:none) ;;
  *)
    echo "Unknown FETCH_GIT_REFS value: $FETCH_GIT_REFS (expected none, shallow or history)"
    exit 1
    ;;
esac

FETCH_TIMEOUT_SECONDS=${FETCH_TIMEOUT_SECONDS:-300}
git_fetch() {
  # full-history-ok: depth_args bounds the history unless FETCH_GIT_REFS is none, in which case nothing is fetched
  timeout --kill-after=30 "$FETCH_TIMEOUT_SECONDS" git fetch --no-tags --force "${depth_args[@]}" "$@"
}

# Runs the given command up to 3 times.
retry() {
  for attempt in {1..3}; do
    if "$@"; then
      return 0
    fi
    echo "Attempt $attempt failed. Retrying..."
    sleep 5
  done
  echo "This was the last attempt. Exiting."
  return 1
}

# Creates the local branch $1 from origin/$1, unless it already exists or origin/$1 was not fetched.
create_local_branch() {
  if ! git show-ref --verify --quiet "refs/heads/$1" && git show-ref --verify --quiet "refs/remotes/origin/$1"; then
    git branch --quiet "$1" "refs/remotes/origin/$1"
  fi
}

git config --global --add safe.directory "$(pwd)"
echo "FETCH_GIT_REFS: $FETCH_GIT_REFS"

if [ "$FETCH_GIT_REFS" != "none" ]; then
  echo "FETCHING ORIGIN MAIN"
  echo "GITHUB_HEAD_REF: ${GITHUB_HEAD_REF:-}"
  head_ref=""
  if [ "$FETCH_GIT_REFS" == "history" ] && [ -n "${GITHUB_HEAD_REF:-}" ] && [ "$GITHUB_HEAD_REF" != "main" ]; then
    head_ref=$GITHUB_HEAD_REF
  fi
  main_refspec='refs/heads/main:refs/remotes/origin/main'
  fetch_main_and_head() {
    if [ -z "$head_ref" ]; then
      git_fetch origin "$main_refspec"
    else
      # On PRs from forks, GITHUB_HEAD_REF is the name of the branch in the forked repo, so we cannot actually fetch that branch directly.
      git_fetch origin "$main_refspec" "refs/heads/${head_ref}:refs/remotes/origin/${head_ref}" || git_fetch origin "$main_refspec"
    fi
  }
  retry fetch_main_and_head
  create_local_branch main
  if [ -n "$head_ref" ]; then
    create_local_branch "$head_ref"
  fi

  echo "FETCHING LATEST RELEASE LINE"
  latest_release=$(cat LATEST_RELEASE)
  retry git_fetch origin "refs/heads/release-line-${latest_release}:refs/remotes/origin/release-line-${latest_release}"
fi

# Compute open-api cache key, that's used in many different caches so we compute it once here.
find . -wholename '*/openapi/*.yaml' | LC_ALL=C sort | xargs sha256sum > openapi-cache-key.txt
