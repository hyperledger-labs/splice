#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 -v <new_release_version> [-n <next_version>]"
  exit 1
}

# Parse flags
NEW_RELEASE_VERSION=""
NEXT_VERSION=""

while getopts ":v:n:" opt; do
  case ${opt} in
    v )
      NEW_RELEASE_VERSION=$OPTARG
      ;;
    n )
      NEXT_VERSION=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if the required arguments are provided
if [ -z "${NEW_RELEASE_VERSION-}" ]; then
  usage
fi

# If next version is not provided, bump the minor version of the new release version
if [ -z "${NEXT_VERSION-}" ]; then
  # shellcheck disable=SC2034
  IFS='.' read -r major minor patch <<< "$NEW_RELEASE_VERSION"
  next_minor=$((minor + 1))
  NEXT_VERSION="$major.$next_minor.0"
fi

# Update LATEST_RELEASE with the new release version
echo "$NEW_RELEASE_VERSION" > "LATEST_RELEASE"

# Update VERSION with the next version
echo "$NEXT_VERSION" > "VERSION"
