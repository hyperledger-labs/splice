#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Deletes all packages (both docker and helm) from ghcr.io where tags match a pattern
set -eou pipefail

VERSION_TO_DELETE=""

while getopts "v:" opt; do
  case $opt in
    v)
      VERSION_TO_DELETE=$OPTARG
      ;;
    *)
      echo "Usage: $0 -v <version_to_delete>"
      exit 1
      ;;
  esac
done
if [ -z "$GH_TOKEN" ]; then
  echo "Error: you need to set $GH_TOKEN."
  exit 1
fi
if [ -z "$VERSION_TO_DELETE" ]; then
  echo "Error: version_to_delete is required."
  echo "Usage: $0 -v <version_to_delete>"
  exit 1
fi

read -r -p "Are you sure you want to delete packages for '$VERSION_TO_DELETE' ? Type 'yes' to continue: " confirmation
if [ "$confirmation" != "yes" ]; then
  echo "Aborted."
  exit 1
fi

CLUSTER_IMAGES_DIR="$SPLICE_ROOT/cluster/images"
CLUSTER_HELM_DIR="$SPLICE_ROOT/cluster/helm"

API_GITHUB_PACKAGES_REPO_HELM="https://api.github.com/orgs/digital-asset/packages/container/decentralized-canton-sync%2Fhelm"
API_GITHUB_PACKAGES_REPO_DOCKER="https://api.github.com/orgs/digital-asset/packages/container/decentralized-canton-sync%2Fdocker"

function delete_version(){
  local dir=$1
  local packages_repo=$2

  if [ -d "$dir" ]; then
    # Get the image name from the directory name
    IMAGE_NAME=$(basename "$dir")
    echo "Checking $IMAGE_NAME"
    if [ ! -f "$dir" ] && [ "$IMAGE_NAME" != "common" ] && [ "$IMAGE_NAME" != "splice-util-lib" ] && [ "$IMAGE_NAME" != "target" ]; then
      tags_found=$(curl -L -s \
                         -H "Accept: application/vnd.github+json" \
                         -H "Authorization: Bearer ${GH_TOKEN}" \
                         -H "X-GitHub-Api-Version: 2022-11-28" \
                         "${packages_repo}%2F${IMAGE_NAME}/versions" \
                         | jq --arg tag_pattern "$VERSION_TO_DELETE" '.[] | select(any(.metadata.container.tags[]; . == $tag_pattern)) | .metadata.container.tags' || true)

      if [ -n "$tags_found" ]; then
        echo "Tags found: $tags_found"
        version_ids=$(curl -L -s \
                           -H "Accept: application/vnd.github+json" \
                           -H "Authorization: Bearer ${GH_TOKEN}" \
                           -H "X-GitHub-Api-Version: 2022-11-28" \
                           "${packages_repo}%2F${IMAGE_NAME}/versions" \
                           | jq  --arg tag_pattern "$VERSION_TO_DELETE" '.[] | select(any(.metadata.container.tags[]; . == $tag_pattern)) | .id' || true)
        nr_ids=$(echo "${version_ids}" | wc -w)

        if [ -n "$version_ids" ]; then
          if [ "$nr_ids" -eq 1 ]; then
            for id in $version_ids; do
              echo "Deleting version $id for $IMAGE_NAME found for version $VERSION_TO_DELETE"
              url="${packages_repo}%2F${IMAGE_NAME}/versions/${id}"
              curl -L \
                -X DELETE \
                -H "Accept: application/vnd.github+json" \
                -H "Authorization: Bearer ${GH_TOKEN}" \
                -H "X-GitHub-Api-Version: 2022-11-28" \
                "$url"
            done
          else
            echo "More than one version found for $IMAGE_NAME with version $VERSION_TO_DELETE"
          fi
        else
          echo "No version found for $IMAGE_NAME with version $VERSION_TO_DELETE"
        fi
      else
        echo "No tags found matching version '$VERSION_TO_DELETE' for $IMAGE_NAME"
      fi
    fi
  fi
}

for dir in  "$CLUSTER_HELM_DIR"/*; do
  packages_repo="$API_GITHUB_PACKAGES_REPO_HELM"
  delete_version "$dir" "$packages_repo"
done

for dir in "$CLUSTER_IMAGES_DIR"/*; do
  packages_repo="$API_GITHUB_PACKAGES_REPO_DOCKER"
  delete_version "$dir" "$packages_repo"
done
