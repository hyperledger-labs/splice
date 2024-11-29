#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

chart=$1

put_helm_chart() {
  source=$1
  full_path=$2
  echo "Publishing helm chart ${source} to ${full_path}"
  response=$(curl -u "${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD}" -sSf -X PUT --upload-file "${source}" "${full_path}")
  sha=$(echo "$response" | jq .checksums.sha256)
  echo "Published ${full_path}, sha256 digest: ${sha}"
}

publish () {
  source=$1
  full_path=$2
  if [[ "$full_path" == *-dirty.tgz ]]; then
    put_helm_chart "${source}" "${full_path}"
  else
    response=$(curl -u "${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD}" -s -o /dev/null -w "%{http_code}" "${full_path}")
    if [ "$response" -eq 200 ]; then
      echo "Helm chart already exists in the repository: ${full_path}"
    else
      put_helm_chart "${source}" "${full_path}"
    fi
  fi
}

artifactory_url="https://digitalasset.jfrog.io/artifactory"
repo="canton-network-helm"
if [[ "$chart" == *-dirty.tgz ]]; then
  repo="canton-network-helm-dev"
else
  if [ -n "${CIRCLE_BRANCH}" ]; then
    if [ "$CIRCLE_BRANCH" == "main" ] || [[ "$CIRCLE_BRANCH" == release-line-* ]]; then
      repo="canton-network-helm"
    else
      repo="canton-network-helm-dev"
    fi
  else
    current_branch=$(git rev-parse --abbrev-ref HEAD)
    echo "Current branch: ${current_branch}"
    if [ "$current_branch" == "main" ] || [[ "$current_branch" == release-line-* ]]; then
      repo="canton-network-helm"
    else
      repo="canton-network-helm-dev"
    fi
  fi
fi

publish "${chart}" "${artifactory_url}/${repo}/digitalasset/$(basename "${chart}")"
