#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# We used to support a feature where we would reset the classes if a full recompilation was requested.
# Since that's not documented anywhere, it probably wasn't really useful, so for now we're just removing it
# (leaving the CCI version of it commented here for reference if needed in the future)
# if [[ << parameters.reset_classes_if_requested >> == true ]] &&
#     grep "^$CIRCLE_BRANCH$" .circleci/branches_to_be_fully_recompiled_in_ci.txt > /dev/null
# then
#   echo "Do not extract precompiled classes, as a full recompilation has been requested."
#   exit 0
# fi

if [[ -e /tmp/classes/classes.tar.gz ]]; then
  tar --use-compress-program=pigz -xf /tmp/classes/classes.tar.gz

  # For some reason, the reset below may change file modification times stamps, if we don't run `git status`.
  git status

  # Reset changes to files under version control.
  git reset --hard

  # Delete untracked files, except for ignored files.
  git clean -fd
  echo "Finished restoring the pre-compiled cache files"

else
  echo "No precompiled classes found. Skipping..."
fi
