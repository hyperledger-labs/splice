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
    n=0
    MAX_RETRIES=5
    # Client.Timeout from ghcr are not fun
    until [ $n -ge $MAX_RETRIES ]; do
      if ! digest=$(get_digest "$app"); then
        digest=""
      fi

      if [ -n "$digest" ]; then
        break
      fi

      n=$((n+1))
      if [ $n -ge $MAX_RETRIES ]; then
        break
      fi
      echo "Failed to get digest for $app, attempt $n/$MAX_RETRIES. Retrying in 5 seconds..." >&2
      sleep 5
    done

    if [ -z "$digest" ]; then
      echo "Failed to get digest for $app after $MAX_RETRIES attempts" >&2
      exit 1
    fi

    a=${app//-/_}
    echo "  $a: \"@$digest\""
  fi
done
