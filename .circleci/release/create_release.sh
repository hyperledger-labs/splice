#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 [-v <release_version>]"
  exit 1
}

# Parse command line arguments
release_version=""
while getopts ":v:" opt; do
  case ${opt} in
    v )
      release_version=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# If release_version is not provided, read from VERSION file
if [ -z "$release_version" ]; then
  if [ ! -f VERSION ]; then
    echo "Error: VERSION file not found."
    exit 1
  fi
  release_version=$(<VERSION)
fi

# Trim leading and trailing whitespace
release_version=$(echo "$release_version" | xargs)

# Check if the version follows semver and is not a snapshot version
if [[ ! "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: Version '$release_version' is not a valid version or is a snapshot version."
  exit 1
fi

"$REPO_ROOT"/.circleci/release/update_version_release_notes.sh -v "$release_version" -f docs/src/release_notes.rst
"$REPO_ROOT"/.circleci/release/update_envrc_vars_release.sh -v "$release_version" -b cluster/deployment -d "mainnet,devnet,testnet"
"$REPO_ROOT"/.circleci/release/push_release_pr.sh -v "$release_version" -b "release_pr_$release_version" -m "Release $release_version"
