#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Function to display usage
usage() {
  echo "Usage: $0 -v <new_version> -b <deployment_dir> -d <directories>"
  exit 1
}

# Parse flags
NEW_VERSION=""
DEPLOYMENT_DIR=""
DIRECTORIES=()

while getopts ":v:b:d:" opt; do
  case ${opt} in
    v )
      NEW_VERSION=$OPTARG
      ;;
    b )
      DEPLOYMENT_DIR=$OPTARG
      ;;
    d )
      IFS=',' read -r -a DIRECTORIES <<< "$OPTARG"
      ;;
    \? )
      usage
      ;;
  esac
done

# Check if the version and deployment directory arguments are provided
if [ -z "${NEW_VERSION-}" ] || [ -z "${DEPLOYMENT_DIR-}" ] || [ ${#DIRECTORIES[@]} -eq 0 ]; then
  usage
fi

# Check if the deployment directory exists
if [[ ! -d $DEPLOYMENT_DIR ]]; then
  echo "Deployment directory $DEPLOYMENT_DIR does not exist"
  exit 1
fi

# Check if all specified directories exist
for dir in "${DIRECTORIES[@]}"; do
  if [[ ! -d $DEPLOYMENT_DIR/$dir ]]; then
    echo "Directory $DEPLOYMENT_DIR/$dir does not exist"
    exit 1
  fi
done

# Function to update the .envrc.vars file
update_envrc_vars() {
  local dir=$1
  local file="$DEPLOYMENT_DIR/$dir/.envrc.vars"

  if [[ -f $file ]]; then
    echo "Updating $file"
    # Use awk to update the file
    awk -v new_version="$NEW_VERSION" '
      BEGIN { OFS = FS = "=" }
      /^[[:space:]]*export[[:space:]]+OVERRIDE_VERSION=/ { $2 = "\"" new_version "\"" }
      /^[[:space:]]*export[[:space:]]+CHARTS_VERSION=/ { $2 = "\"" new_version "\"" }
      /^[[:space:]]*export[[:space:]]+MULTI_VALIDATOR_IMAGE_VERSION=/ { $2 = "\"" new_version "\"" }
      { print }
    ' "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
  else
    echo "$file does not exist"
    exit 1
  fi
}

# Loop through directories and update .envrc.vars files
for dir in "${DIRECTORIES[@]}"; do
  update_envrc_vars "$dir"
done
