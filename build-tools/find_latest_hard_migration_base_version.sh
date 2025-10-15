#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# On most days this script does the same thing as `build-tools/find_latest_base_version.sh`, which is authoritative for regular upgrades.
# Regular upgrades and hard migrations break under different conditions though, hence we use a separate script to separate the version selection logic more cleanly.

set -euo pipefail

# latest_release=$(cat "$SPLICE_ROOT/LATEST_RELEASE")
# echo "release-line-${latest_release}"

# TODO(DACH-NY/canton-network-internal#2180): revert this to release-line-${latest_release} once latest_release is 0.4.21
echo "0.4.20-with-auth0-changes"
