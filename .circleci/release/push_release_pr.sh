#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
# This script is expected to be executed in the $REPO_ROOT directory
# Function to display usage
usage() {
  echo "Usage: $0 -v <release_version> -b <branch_name> -m <commit_message>"
  exit 1
}

# Parse flags
RELEASE_VERSION=""
BRANCH_NAME=""
COMMIT_MESSAGE=""

while getopts ":v:b:m:" opt; do
  case ${opt} in
    v )
      RELEASE_VERSION=$OPTARG
      ;;
    b )
      BRANCH_NAME=$OPTARG
      ;;
    m )
      COMMIT_MESSAGE=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if the required arguments are provided
if [ -z "${RELEASE_VERSION-}" ] || [ -z "${BRANCH_NAME-}" ] || [ -z "${COMMIT_MESSAGE-}" ]; then
  usage
fi

if [ "${CI-}" ==  true ]; then
  if [ -z "${GITHUB_TOKEN-}" ]; then
    echo "Env var GITHUB_TOKEN must be set"
    exit 1
  fi
  git remote set-url origin "https://canton-network-da:${GITHUB_TOKEN}@github.com/DACH-NY/canton-network-node.git"
fi

# Create branch if it does not exist, otherwise checkout the branch
if git show-ref --verify --quiet refs/heads/"$BRANCH_NAME"; then
  git checkout "$BRANCH_NAME"
else
  git checkout -b "$BRANCH_NAME"
fi

# Only add the files that are needed to create a release PR
git add cluster/deployment/devnet/.envrc.vars
git add cluster/deployment/testnet/.envrc.vars
git add cluster/deployment/mainnet/.envrc.vars
git add docs/src/release_notes.rst

# Create a commit with the specified message
git commit -m "$COMMIT_MESSAGE"

"$REPO_ROOT"/.circleci/release/update_latest_release.sh -v "$RELEASE_VERSION"
git add VERSION LATEST_RELEASE
git commit -m "Updated LATEST_RELEASE to $RELEASE_VERSION and, if needed, the VERSION"

# Push the changes to the specified branch
git push --force-with-lease --set-upstream origin "$BRANCH_NAME"

# Create a pull request on GitHub using the GitHub CLI
gh pr create --title "$COMMIT_MESSAGE" --base main --head "$BRANCH_NAME"
