#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

function usage() {
  echo "Usage: $0 -[h] [-w]"
  echo "  -h: Show this help message"
  echo "  -w: Wait for the SV node to be ready"
}

wait=0
while getopts "hw" opt; do
  case ${opt} in
    h)
      usage
      exit 0
      ;;
    w)
      wait=1
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ -z "${IMAGE_TAG:-}" ]; then
  if [ ! -f "${script_dir}/../VERSION" ]; then
    _error_msg "Could not derive image tags automatically, ${script_dir}/../VERSION is missing. Please make sure that file exists, or export an image tag in IMAGE_TAG"
    exit 1
  else
    IMAGE_TAG=$(cat "${script_dir}/../VERSION")
    _info "Using version ${IMAGE_TAG}"
    export IMAGE_TAG
  fi
fi

extra_args=()
if [ $wait -eq 1 ]; then
  extra_args+=("--wait" "--wait-timeout" "600")
fi
docker compose -f "${script_dir}/compose.yaml" up -d "${extra_args[@]}"
