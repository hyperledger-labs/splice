#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Copies release helm charts for app charts defined in app_charts_file from artifactory to ghcr.io
set -eou pipefail

VERSION=""
APP_CHARTS_FILE=""

while getopts "v:f:" opt; do
  case $opt in
    v)
      VERSION=$OPTARG
      ;;
    f)
      APP_CHARTS_FILE=$OPTARG
      ;;
    *)
      echo "Usage: $0 -v <version> [-f <app_charts_file>]"
      exit 1
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "Error: Version is required."
  echo "Usage: $0 -v <version> [-f <app_charts_file>]"
  exit 1
fi

if [ -z "${APP_CHARTS_FILE:-}" ]; then
  APP_CHARTS_FILE="/dev/stdin"
fi

if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_USER:-}" ]; then
  echo "Error: you need to set GITHUB_TOKEN and GITHUB_USER."
  exit 1
fi

echo "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_USER" --password-stdin

APP_CHARTS=$(<"$APP_CHARTS_FILE")

ARTIFACTORY_REGISTRY="https://digitalasset.jfrog.io/artifactory/canton-network-helm"
GITHUB_REGISTRY="ghcr.io/digital-asset/decentralized-canton-sync/helm"

helm repo add artifactory-helm \
  "$ARTIFACTORY_REGISTRY" \
  --username "$ARTIFACTORY_USER" \
  --password "$ARTIFACTORY_PASSWORD"

helm repo update

TMP_DIR=$(mktemp -d)

if [ ! -d "$TMP_DIR" ]; then
  echo "Failed to create temporary directory"
  exit 1
fi

# Iterate through the APP_CHARTS, use make cluster/helm/write-app-charts to generate this list
for CHART_NAME in $APP_CHARTS; do
  for i in {1..10}; do
    if helm pull artifactory-helm/"$CHART_NAME" --version "$VERSION" --destination "$TMP_DIR" && \
       helm push "$TMP_DIR/$CHART_NAME-$VERSION.tgz" oci://"$GITHUB_REGISTRY"; then
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
