#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

source "${TOOLS_LIB}/libcli.source"

python_cli=(/usr/bin/env python3)

# This function requires dockerfile-parse for Python to be installed
get_base_image() {
  local dockerfile="$1"
  "${python_cli[@]}" -c 'from sys import argv; from dockerfile_parse import DockerfileParser; print(DockerfileParser(argv[1]).baseimage)' "${dockerfile}"
}

dockerfiles_find=$(find cluster/images/ -name Dockerfile)

IFS=$'\n' read -d '' -ra \
  dockerfiles < <(echo "$dockerfiles_find"; echo -e '\0')

for dockerfile in "${dockerfiles[@]}"; do
  # We exclude "${base_version}", which we use for base images that are built from this repo in the same version
  # and parameterized digests which we use for the Canton images
  # shellcheck disable=SC2016
  if grep -Hn "^\s*FROM\b" "$dockerfile" | grep -v ':\${base_version}' | grep -v '@${image_sha256}' &&
    get_base_image "$dockerfile" | grep -vq '@sha256:'; then
    _error "docker image '$dockerfile' must pin base image to a specific digest"
  fi
done
