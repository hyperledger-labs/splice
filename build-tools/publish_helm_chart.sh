#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

chart=$1

publish () {
  source=$1
  full_path=$2
  echo "Publishing ${source} to ${full_path}"
  curl -u "${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD}" -sSf -X PUT --upload-file "${source}" "${full_path}"
}

artifactory_url="https://digitalasset.jfrog.io/artifactory"
repo="canton-network-helm"
if [[ "$chart" == *-dirty ]]; then
  repo="canton-network-helm-dev"
else
  current_branch=$(git rev-parse --abbrev-ref HEAD)
  if [ "$current_branch" == "main" ] || [[ "$current_branch" == release-line-* ]]; then
    repo="canton-network-helm"
  else
    repo="canton-network-helm-dev"
  fi
fi

publish "${chart}" "${artifactory_url}/${repo}/digitalasset/$(basename "${chart}")"
