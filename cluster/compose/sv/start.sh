#!/usr/bin/env bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

function usage() {
  echo "Usage: $0 -[h] [-w] [-E]"
  echo "  -h: Show this help message"
  echo "  -w: Wait for the SV node to be ready"
  echo "  -E: Use this flag to bind the Nginx proxy to 0.0.0.0 (external access) instead of 127.0.0.1 (default)."
}

# issue a user friendly red error
function _error_msg(){
  # shellcheck disable=SC2145
  echo -e "\e[1;31mERROR: $@\e[0m" >&2
}

# issue a user friendly green informational message
function _info(){
  local first_line="INFO: "
  while read -r; do
    printf -- "\e[32;1m%s%s\e[0m\n" "${first_line:-     }" "${REPLY}"
    unset first_line
  done < <(echo -e "$@")
}

wait=0
HOST_BIND_IP="127.0.0.1"

while getopts "hwE" opt; do
  case ${opt} in
    h)
      usage
      exit 0
      ;;
    w)
      wait=1
      ;;
    E)
      HOST_BIND_IP="0.0.0.0"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ -z "${IMAGE_TAG:-}" ]; then
  if [ ! -f "${script_dir}/../../VERSION" ]; then
    _error_msg "Could not derive image tags automatically, ${script_dir}/../../VERSION is missing. Please make sure that file exists, or export an image tag in IMAGE_TAG"
    exit 1
  else
    IMAGE_TAG=$(cat "${script_dir}/../../VERSION")
    _info "Using version ${IMAGE_TAG}"
    export IMAGE_TAG
  fi
fi

extra_args=()
if [ $wait -eq 1 ]; then
  extra_args+=("--wait" "--wait-timeout" "600")
fi

export HOST_BIND_IP

docker compose -f "${script_dir}/compose.yaml" up -d "${extra_args[@]}"
