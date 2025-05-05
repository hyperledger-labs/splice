#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -e

if [ -z "${1:-}" ]; then
    echo "Usage: $0 <halt_file_in_SPLICE_ROOT>"
else
    halt_file=$1
fi

sha=$(git rev-parse HEAD)
git_commit_message=$(git log -1 --pretty=format:%B "$sha")
if [[ "${git_commit_message}" == *"[release]"* ]] && [[ ! "${CIRCLE_BRANCH}" =~ ^release-line- ]]; then
  echo "commit message contains [release] but branch '${CIRCLE_BRANCH}' is not a release line"
  # Running a circleci-agent command from within run_bash_command_in_nix does not work, so we create a file
  # that represents the need to halt, and call `circleci-agent step halt` in a separate `run` step
  touch "$SPLICE_ROOT"/"$halt_file"
fi
