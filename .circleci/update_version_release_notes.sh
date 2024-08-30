#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 -f <file> -r <new_version>"
  exit 1
}

# Parse flags
while getopts ":f:r:" opt; do
  case ${opt} in
    f )
      FILE=$OPTARG
      ;;
    r )
      NEW_VERSION=$OPTARG
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if both flags are provided
if [ -z "${FILE-}" ] || [ -z "${NEW_VERSION-}" ]; then
  usage
fi

# Check if file exists
if [[ ! -f "$FILE" ]]; then
  echo "Error: File '$FILE' not found."
  exit 1
fi

# Run Sphinx to check the file
TEMP_DIR=$(mktemp -d)
trap 'rm -rf $TEMP_DIR' EXIT

cat > "$TEMP_DIR/conf.py" <<EOL
project = 'Linting Project'
extensions = []
EOL

if ! sphinx-lint "$FILE" > /dev/null 2>&1; then
  echo "Error: '$FILE' sphinx linting failed, to see details run: sphinx-lint $FILE"
  exit 1
fi

# Check if the new version header and its underline already exist anywhere in the file
if ! awk -v new_version="$NEW_VERSION" '
  BEGIN { found = 0 }
  {
    if ($0 == new_version) {
      getline
      if ($0 ~ /^-+$/ && length($0) == length(new_version)) {
        found = 1
        exit 1
      }
    }
  }
  END { if (found) exit 1 }
' "$FILE"; then
  echo "Error: Release version '$NEW_VERSION' section title already exists in '$FILE'."
  exit 1
fi

# Check if "Upcoming" header exists and replace it
if ! awk -v new_version="$NEW_VERSION" '
  BEGIN { found = 0 }
  /^Upcoming$/ {
    getline
    if ($0 ~ /^-+$/ && length($0) == length("Upcoming")) {
      underline=gensub(/./, "-", "g", new_version)
      print new_version
      print underline
      found = 1
      next
    } else {
      exit 1
    }
  }
  { print }
  END { if (!found) exit 1 }
' "$FILE" > "${FILE}.tmp"; then
  echo "Error: No 'Upcoming' section title found in '$FILE'."
  rm -f "${FILE}.tmp"
  exit 1
fi

# Run Sphinx to check the modified file
if ! sphinx-lint "$FILE" > /dev/null 2>&1; then
  echo "Error: Modified '$FILE' did not pass sphinx linting, to see details run: sphinx-lint $FILE"
  rm -f "${FILE}.tmp"
  exit 1
fi

# Replace the original file with the modified file
mv "${FILE}.tmp" "$FILE"

echo "Updated Upcoming section title, sphinx linting passed."
