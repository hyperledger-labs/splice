#! /usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# If we are on a release branch, or on a commit that is on a release branch (but not on main),
# prints the version number of the release. Otherwise, exits silently with 0.

# If we're on a release branch, just return the version number.
branch=$(git rev-parse --abbrev-ref HEAD)
if [[ $branch =~ ^release-line-[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "${branch#release-line-}"
    exit 0
fi

# Check if we are on a commit that is on a release branch but not on the main branch.
# (when checking out Splice as a submodule, we might be losing the branch information).
sha=$(git rev-parse HEAD)
if ! (git branch -r --contains "$sha" | grep -q "\borigin/main\b"); then
    if [[ $(git branch -r --contains "$sha") =~ origin/release-line-[0-9]+\.[0-9]+\.[0-9]+ ]]; then
        branch=$(git branch -r --contains "$sha" | grep -o 'origin/release-line-[0-9]\+\.[0-9]\+\.[0-9]\+' | head -n 1)
        echo "${branch#origin/release-line-}"
        exit 0
    fi
fi

exit 0
