#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# On most days this script does the same thing as `build-tools/find_latest_base_version.sh`, which is authoritative for regular upgrades.
# Regular upgrades and hard migrations break under different conditions though, hence we use a separate script to separate the version selection logic more cleanly.

set -euo pipefail

# TODO(#14679) Switch this back to LATEST_RELEASE after mainnet upgraded
# latest_release=$(cat "LATEST_RELEASE")
# echo "release-line-${latest_release}"
echo release-line-0.2.4
