#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 -v <release_version> -c <release_commit_ref>"
  exit 1
}

# Parse flags
RELEASE_VERSION=""
RELEASE_COMMIT_REF=""

while getopts ":v:c:" opt; do
  case ${opt} in
    v )
      RELEASE_VERSION=$OPTARG
      ;;
    c )
      RELEASE_COMMIT_REF=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if the required arguments are provided
if [ -z "${RELEASE_VERSION-}" ] || [ -z "${RELEASE_COMMIT_REF-}" ]; then
  usage
fi

# Check if the base branch has a last commit with a [release] footer in the message
LAST_COMMIT_MESSAGE=$(git log -1 --pretty=%B "$RELEASE_COMMIT_REF")
if [[ "$LAST_COMMIT_MESSAGE" != *"[release]"* ]]; then
  echo "The release_commit_ref '$RELEASE_COMMIT_REF' does not have a [release] footer in the message"
  exit 1
fi

RELEASE_LINE_BRANCH_NAME="release-line-${RELEASE_VERSION}"
if git show-ref --verify --quiet "refs/heads/$RELEASE_LINE_BRANCH_NAME"; then
  echo "Release line branch $RELEASE_LINE_BRANCH_NAME already exists"
  exit 1
fi

# Create a new branch named release-line-0.x.y from the specified base branch
git checkout -b "$RELEASE_LINE_BRANCH_NAME" "$RELEASE_COMMIT_REF"

## Push the new branch to the remote repository
git push --set-upstream origin "$RELEASE_LINE_BRANCH_NAME"

# Trigger a CircleCI pipeline on that branch with the job publish-public-artifacts
if [ -z "${CIRCLECI_TOKEN-}" ]; then
  echo "Env var CIRCLECI_TOKEN must be set"
  exit 1
fi

curl -u "${CIRCLECI_TOKEN}:" -X POST --header "Content-Type: application/json" \
  -d '{
        "branch": "'"${RELEASE_LINE_BRANCH_NAME}"'",
       "parameters": {
          "run-job": "publish-public-artifacts"
        }
      }' \
  "https://circleci.com/api/v2/project/gh/DACH-NY/canton-network-node/pipeline"
