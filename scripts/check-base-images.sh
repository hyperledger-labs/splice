#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

source "${TOOLS_LIB}/libcli.source"

# We exclude "base_version", which we use for base images that are built from this repo in the same version

if find cluster/images/ -name Dockerfile -exec grep -Hn "FROM" {} + | grep -v sha | grep -v base_version; then
  _error "docker images must pin base images to a specific digest"
fi

