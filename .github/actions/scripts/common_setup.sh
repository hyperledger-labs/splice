#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Checkout relevant git branches and tags
# Note that we need to checkout main and the current branch also because the todo checker uses
# `git log main..$BRANCH` so both main and the branch have to be local refs

git config --global --add safe.directory "$(pwd)"
git fetch --tags --force origin
git checkout main
if [ "$GITHUB_HEAD_REF" != "main" ]; then
  git checkout -B "$GITHUB_HEAD_REF"
fi
git fetch origin 'refs/heads/release-line*:refs/heads/origin/release-line*' --force

if [ -n "${GITHUB_SHA:-}" ]; then
  git checkout "$GITHUB_SHA"
fi

# Compute open-api cache key, that's used in many different caches so we compute it once here.
find . -wholename '*/openapi/*.yaml' | LC_ALL=C sort | xargs sha256sum > openapi-cache-key.txt


# Since we currently use stock ubuntu image for running workflows, there are some missing
# dependencies in the image. For now we install them here.
# In the future, we probably want to build a custom image with all the dependencies pre-installed.

# Retry because we've seen Ubuntu be down sometimes.

n=0
until [ $n -gt 50 ]; do
  sudo apt update -o APT::Update::Error-Mode=any && break
  n=$((n+1))
  sleep 10
done
n=0
until [ $n -gt 50 ]; do
  sudo apt install git curl xz-utils pigz rsync -y && break
  n=$((n+1))
  sleep 10
done
