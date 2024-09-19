#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

function push_with_retries() {
  n=0
  until [ "$n" -ge 5 ]; do
    git push origin HEAD && break
    n=$((n+1))
    sleep 5
  done
  if [ "$n" -ge 5 ]; then
    echo "Failed to push to branch $branch"
    exit 1
  fi
}


if [ $# -ne 1 ]; then
  echo "Usage: $0 <splice-dir>"
  exit 1
fi

splice_dir=$1

commit=$(git rev-parse --short HEAD)
version=$(get-snapshot-version)

cd "$splice_dir"

# ssh is not in the path in nix in CCI, so helping git find it
export GIT_SSH_COMMAND="/usr/bin/ssh"

git config user.email "splice-maintainers@digitalasset.com"
git config user.name "DA Automation"

branch="splice-cci-update-$(date +%s)"
base_branch="$CIRCLE_BRANCH"
if [ "$base_branch" != "main" ]; then
  remote_branch=$(git ls-remote --heads origin "refs/heads/$base_branch")
  if [ -z "$remote_branch" ]; then
    echo "Branch $base_branch does not exist in the remote repository, creating it"
    git checkout -b "$base_branch"
    push_with_retries
  fi
fi

git checkout -b "$branch"
git add .
git commit -s -m "Update Splice from CCI" --allow-empty

push_with_retries

n=0
until [ "$n" -ge 5 ]; do
  gh pr create --title "Update Splice $base_branch to version $version (automatic PR)" \
    --body "This PR updates branch $base_branch of Splice from the latest changes as of version $version, commit DACH-NY/canton-network-node@$commit" \
    --base "$base_branch" \
    --reviewer isegall-da,moritzkiefer-da,nicu-da,martinflorian-da,ray-roestenburg-da && break
   n=$((n+1))
   sleep 5
done
if [ "$n" -ge 5 ]; then
  echo "Failed to create PR"
  exit 1
fi
