#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Logs how much git data this job's checkout holds, to measure the effect of
# shallow fetches (#7831). Informational only: never fails the job.

set -uo pipefail

# TODO(#7831): deliberate left-over TODO to test the todo checker under FETCH_GIT_REFS=history; do not merge

git config --global --add safe.directory "$(pwd)" || true

echo "git dir size: $(du -sh "$(git rev-parse --git-common-dir)" | cut -f1)"
echo "shallow: $(git rev-parse --is-shallow-repository)"
echo "partial clone filter: $(git config --get remote.origin.partialclonefilter || echo none)"
echo "commits reachable from HEAD: $(git rev-list --count HEAD)"
echo "remote refs:"
git for-each-ref --format='  %(refname)' refs/remotes
git count-objects -vH
exit 0
