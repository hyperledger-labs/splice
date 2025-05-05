#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

function get_digest() {
  img=$1

  img_name=$(get-docker-image-reference "$img")
  # The docker image is multi-arch, but the digests are per architecture. We support amd64 clusters only, so pick that digest.
  docker manifest inspect "$img_name" | jq -r '.manifests[] | select(.platform.architecture=="amd64") | .digest'
}

echo "imageDigests:"
for dir in "${SPLICE_ROOT}"/cluster/images/*; do
  app=$(basename "$dir");
  if [ ! -f "$dir" ] && [ "$app" != "common" ]; then
    digest=$(get_digest "$app")
    a=${app//-/_}
    echo "  $a: \"@$digest\""
  fi
done
