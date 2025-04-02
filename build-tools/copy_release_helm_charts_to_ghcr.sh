#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Copies release helm charts for app charts defined in app_charts_file from artifactory to ghcr.io
set -eou pipefail

VERSION=""
VERSIONS_FILE=""
APP_CHARTS_FILE=""

while getopts "v:f:-:" opt; do
  case $opt in
    v)
      VERSION=$OPTARG
      ;;
    f)
      APP_CHARTS_FILE=$OPTARG
      ;;
    -)
      case "${OPTARG}" in
        versions)
          VERSIONS_FILE="${!OPTIND}"; OPTIND=$((OPTIND + 1))
          ;;
        *)
          echo "Usage: $0 -v <version> | --versions <versions_file> [-f <app_charts_file>]"
          exit 1
          ;;
      esac
      ;;
    *)
      echo "Usage: $0 -v <version> | --versions <versions_file> [-f <app_charts_file>]"
      exit 1
      ;;
  esac
done

if [ -z "$VERSION" ] && [ -z "$VERSIONS_FILE" ]; then
  echo "Error: Either -v <version> or --versions <versions_file> must be provided."
  echo "Usage: $0 -v <version> | --versions <versions_file> [-f <app_charts_file>]"
  exit 1
fi

if [ -n "$VERSION" ] && [ -n "$VERSIONS_FILE" ]; then
  echo "Error: Only one of -v <version> or --versions <versions_file> can be provided."
  echo "Usage: $0 -v <version> | --versions <versions_file> [-f <app_charts_file>]"
  exit 1
fi

if [ -z "${APP_CHARTS_FILE:-}" ]; then
  APP_CHARTS_FILE="/dev/stdin"
fi

if [ -z "${GH_TOKEN:-}" ] || [ -z "${GH_USER:-}" ]; then
  echo "Error: you need to set GH_TOKEN and GH_USER."
  exit 1
fi

echo "$GH_TOKEN" | docker login ghcr.io -u "$GH_USER" --password-stdin

APP_CHARTS=$(<"$APP_CHARTS_FILE")

SRC_REGISTRY="${DEV_HELM_REGISTRY}"
DEST_REGISTRY="${RELEASE_HELM_REGISTRY}"

TMP_DIR=$(mktemp -d)

if [ ! -d "$TMP_DIR" ]; then
  echo "Failed to create temporary directory"
  exit 1
fi

if [ -n "$VERSIONS_FILE" ]; then
  VERSIONS=$(<"$VERSIONS_FILE")
else
  VERSIONS="$VERSION"
fi

for VERSION in $VERSIONS; do
  # Iterate through the APP_CHARTS, use make cluster/helm/write-app-charts to generate this list
  for CHART_NAME in $APP_CHARTS; do
    for i in {1..10}; do
      if helm pull oci://"$SRC_REGISTRY"/"$CHART_NAME" --version "$VERSION" --destination "$TMP_DIR" && \
         helm push "$TMP_DIR/$CHART_NAME-$VERSION.tgz" oci://"$DEST_REGISTRY"; then
        echo "Successfully copied $CHART_NAME:$VERSION"
        break
      else
        echo "Failed to copy $CHART_NAME:$VERSION (attempt $i)"
        if [ "$i" -eq 10 ]; then
          echo "Max retries reached. Exiting."
          exit 1
        fi
        sleep 5
      fi
    done
  done
done
