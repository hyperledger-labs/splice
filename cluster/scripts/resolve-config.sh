#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Updates the resolved config for the current cluster which is specified either with TARGET_CLUSTER
# or the current working directory.

if [ -z "${TARGET_CLUSTER-}" ]; then
  cluster_dir="."
else
  cluster_dir="${DEPLOYMENT_DIR}/${TARGET_CLUSTER}"
fi
resolved_config_file="$cluster_dir/config.resolved.yaml"

# resolve make parameters to allow running both for splice and internal clusters
make_directory="${cluster_dir}/../../.."
resolved_config_target="$(realpath --no-symlinks --relative-to "$make_directory" "$resolved_config_file")"
# make resolved config to apply local config changes
make --directory "$make_directory" "$resolved_config_target" > /dev/null
