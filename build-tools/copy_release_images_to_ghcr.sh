#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Copies release docker images from artifactory to ghcr.io
# skopeo is used to copy multi-arch images correctly
# Note: skopeo in nix does not work, complains about policy.json, version from brew install (skopeo version 1.17.0) works fine.
# There could be a fix here, though it is a super old link ¯\_(ツ)_/¯: https://github.com/NixOS/nixpkgs/commit/365d07cea0446cbdc3d2c89502ce62c1f283989b
set -eou pipefail

VERSION=""
VERSIONS_FILE=""
IMAGES_FILE=""
SRC_REGISTRY="${DEV_DOCKER_REGISTRY}"
DEST_REGISTRY="${RELEASE_DOCKER_REGISTRY}"

while getopts "v:f:-:" opt; do
  case $opt in
    v)
      VERSION=$OPTARG
      ;;
    f)
      IMAGES_FILE=$OPTARG
      ;;
    -)
      case "${OPTARG}" in
        versions)
          VERSIONS_FILE="${!OPTIND}"; OPTIND=$((OPTIND + 1))
          ;;
        *)
          echo "Usage: $0 -v <version> | --versions <versions_file> [-f <images_file>]"
          exit 1
          ;;
      esac
      ;;
    *)
      echo "Usage: $0 -v <version> | --versions <versions_file> [-f <images_file>]"
      exit 1
      ;;  esac
done

if [ -z "$VERSION" ] && [ -z "$VERSIONS_FILE" ]; then
  echo "Error: Either -v <version> or --versions <versions_file> must be provided."
  echo "Usage: $0 -v <version> | --versions <versions_file> [-f <images_file>]"
  exit 1
fi

if [ -n "$VERSION" ] && [ -n "$VERSIONS_FILE" ]; then
  echo "Error: Only one of -v <version> or --versions <versions_file> can be provided."
  echo "Usage: $0 -v <version> | --versions <versions_file> [-f <images_file>]"
  exit 1
fi

if [ -z "${IMAGES_FILE:-}" ]; then
  IMAGES_FILE="/dev/stdin"
fi

if [ -z "${GH_TOKEN:-}" ] || [ -z "${GH_USER:-}" ]; then
  echo "Error: you need to set GH_TOKEN and GH_USER."
  exit 1
fi

IMAGES=$(<"$IMAGES_FILE")

# for skopeo to work, we need to set the XDG_RUNTIME_DIR, in CCI it cannot mkdir /run/containers: permission denied
export XDG_RUNTIME_DIR=/tmp/containers
mkdir -p "$XDG_RUNTIME_DIR"

echo "$GH_TOKEN" | skopeo login "$SRC_REGISTRY" --username "$GH_USER" --password-stdin
echo "$GH_TOKEN" | skopeo login "$DEST_REGISTRY" --username "$GH_USER" --password-stdin

if [ -n "$VERSIONS_FILE" ]; then
  VERSIONS=$(<"$VERSIONS_FILE")
else
  VERSIONS="$VERSION"
fi

for VERSION in $VERSIONS; do
  # Iterate through the directories in the cluster images directory
  for IMAGE_NAME in $IMAGES; do
    if [ "$IMAGE_NAME" == "pulumi-kubernetes-operator" ]; then
      TAG="v${VERSION}"
    else
      TAG="$VERSION"
    fi
    # Construct the full image names
    SOURCE_IMAGE="$SRC_REGISTRY/$IMAGE_NAME:$TAG"
    TARGET_IMAGE="$DEST_REGISTRY/$IMAGE_NAME:$TAG"

    for i in {1..10}; do
      # Some images have been copied before from Artifactory, which is not used anymore.
      # Artifactory has unknown/unknown attestation manifests, which show up as unknown/unknown os/architecture manifests. There is nothing inherently wrong with this.
      # skopeo on nix does not bundle the policy.json file, so we need to provide it.
      if skopeo copy --policy "${SPLICE_ROOT}"/build-tools/skopeo_policy.json --all docker://"$SOURCE_IMAGE" docker://"$TARGET_IMAGE"; then
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
done
