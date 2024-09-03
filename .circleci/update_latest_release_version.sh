#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 -r <new_release_version> [-n <next_version>] -b <branch_name>"
  exit 1
}

# Parse flags
NEW_RELEASE_VERSION=""
NEXT_VERSION=""
BRANCH_NAME=""

while getopts ":r:n:b:" opt; do
  case ${opt} in
    r )
      NEW_RELEASE_VERSION=$OPTARG
      ;;
    n )
      NEXT_VERSION=$OPTARG
      ;;
    b )
      BRANCH_NAME=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if the required arguments are provided
if [ -z "${NEW_RELEASE_VERSION-}" ] || [ -z "${BRANCH_NAME-}" ]; then
  usage
fi

if [ "${CI-}" ==  true ]; then
  if [ -z "${GITHUB_TOKEN-}" ]; then
    echo "Env var GITHUB_TOKEN must be set"
    exit 1
  fi
  git remote set-url origin "https://canton-network-da:${GITHUB_TOKEN}@github.com/DACH-NY/canton-network-node.git"
fi

# Create and checkout a new branch
git checkout -b "$BRANCH_NAME"

# If next version is not provided, bump the minor version of the new release version
if [ -z "${NEXT_VERSION-}" ]; then
  IFS='.' read -r major minor <<< "$NEW_RELEASE_VERSION"
  next_minor=$((minor + 1))
  NEXT_VERSION="$major.$next_minor.0"
fi

# Update LATEST_RELEASE with the new release version
echo "$NEW_RELEASE_VERSION" > "LATEST_RELEASE"

# Update VERSION with the next version
echo "$NEXT_VERSION" > "VERSION"

# Add the modified files to git
git add "LATEST_RELEASE" "VERSION"

# Commit the changes
git commit -m "Updated LATEST_VERSION to $NEW_RELEASE_VERSION, VERSION to $NEXT_VERSION"

# Push the branch to the remote repository
git push --force-with-lease --set-upstream origin "$BRANCH_NAME"

# Create a pull request on GitHub
gh pr create --title "Update version and latest release" --body "Updated LATEST_VERSION to $NEW_RELEASE_VERSION, VERSION to $NEXT_VERSION" --base main --head "$BRANCH_NAME"
