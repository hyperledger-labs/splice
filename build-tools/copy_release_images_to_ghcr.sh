#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Copies release docker images from artifactory to ghcr.io
# skopeo is used to copy multi-arch images correctly
# Note: skopeo in nix does not work, complains about policy.json, version from brew install (skopeo version 1.17.0) works fine.
# There could be a fix here, though it is a super old link ¯\_(ツ)_/¯: https://github.com/NixOS/nixpkgs/commit/365d07cea0446cbdc3d2c89502ce62c1f283989b
set -eou pipefail

VERSION=""
IMAGES_FILE=""

while getopts "v:f:" opt; do
  case $opt in
    v)
      VERSION=$OPTARG
      ;;
    f)
      IMAGES_FILE=$OPTARG
      ;;
    *)
      echo "Usage: $0 -v <version> [-f <images_file>]"
      exit 1
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "Error: Version is required."
  echo "Usage: $0 -v <version> [-f <images_file>]"
  exit 1
fi

if [ -z "${IMAGES_FILE:-}" ]; then
  IMAGES_FILE="/dev/stdin"
fi

if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_USER:-}" ]; then
  echo "Error: you need to set GITHUB_TOKEN and GITHUB_USER."
  exit 1
fi

IMAGES=$(<"$IMAGES_FILE")

# Set the registries
ARTIFACTORY_REGISTRY_SERVER="digitalasset-canton-network-docker.jfrog.io"
ARTIFACTORY_REGISTRY="$ARTIFACTORY_REGISTRY_SERVER/digitalasset"
GITHUB_REGISTRY="ghcr.io/digital-asset/decentralized-canton-sync/docker"

echo "$ARTIFACTORY_PASSWORD" | skopeo login "$ARTIFACTORY_REGISTRY_SERVER" --username "$ARTIFACTORY_USER" --password-stdin
echo "$GITHUB_TOKEN" | skopeo login ghcr.io --username "$GITHUB_USER" --password-stdin

# Iterate through the directories in the cluster images directory
for IMAGE_NAME in $IMAGES; do
  if [ "$IMAGE_NAME" == "pulumi-kubernetes-operator" ]; then
    TAG="v${VERSION}"
  else
    TAG="$VERSION"
  fi
  # Construct the full image names
  SOURCE_IMAGE="$ARTIFACTORY_REGISTRY/$IMAGE_NAME:$TAG"
  TARGET_IMAGE="$GITHUB_REGISTRY/$IMAGE_NAME:$TAG"

  # for skopeo to work, we need to set the XDG_RUNTIME_DIR, in CCI it cannot mkdir /run/containers: permission denied
  export XDG_RUNTIME_DIR=/tmp/containers
  mkdir -p "$XDG_RUNTIME_DIR"

  for i in {1..10}; do
    # Artifactory has unknown/unknown attestation manifests, which show up as unknown/unknown os/architecture manifests. There is nothing inherently wrong with this.
    # skopeo on nix does not bundle the policy.json file, so we need to provide it.
    if skopeo copy --policy "${REPO_ROOT}"/build-tools/skopeo_policy.json --all docker://"$SOURCE_IMAGE" docker://"$TARGET_IMAGE"; then
      echo "Successfully copied $SOURCE_IMAGE to $TARGET_IMAGE"
      break
    else
      echo "Failed to copy $SOURCE_IMAGE to $TARGET_IMAGE (attempt $i)"
      if [ "$i" -eq 10 ]; then
        echo "Max retries reached. Exiting."
        exit 1
      fi
      sleep 5
    fi
  done
done
