#! /usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#15454): remove this once we're more confident that pulumi won't make the repo dirty

set -euo pipefail

slack_message_suffix=$1

if [[  $(git diff --stat) != '' || $(git diff --cached) != '' ]]; then
  echo "Repo is dirty on CI"
  git status
  git --no-pager diff --cached
  # post-slack-message skips the notification if the message contains "cluster scratchnet" ...
  .circleci/post-slack-message.sh "Repo is dirty on CI; resetting. ( $slack_message_suffix )"
  echo "Resetting repo..."
  git reset HEAD --hard
fi
