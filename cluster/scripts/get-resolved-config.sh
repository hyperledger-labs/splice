#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Updates and prints the resolved config to stdout for the current cluster which is specified
# either with TARGET_CLUSTER or the current working directory.

if [ -z "${TARGET_CLUSTER-}" ]; then
  cluster_dir="."
else
  cluster_dir="${DEPLOYMENT_DIR}/${TARGET_CLUSTER}"
fi

resolved_config_file="$cluster_dir/config.resolved.yaml"
# if we are not in CI we might have some local config changes and the resolved config was not yet updated
if [ -z "${CI:-}" ]; then
  "${SPLICE_ROOT}/cluster/scripts/resolve-config.sh"
fi

# using resolved config to avoid duplicating config loader implementation
cat "$resolved_config_file"
