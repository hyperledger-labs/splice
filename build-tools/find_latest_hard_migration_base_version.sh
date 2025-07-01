#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# On most days this script does the same thing as `build-tools/find_latest_base_version.sh`, which is authoritative for regular upgrades.
# Regular upgrades and hard migrations break under different conditions though, hence we use a separate script to separate the version selection logic more cleanly.

set -euo pipefail

# TODO(DACH-NY/canton-network-interna#713) Reset back to latest_release after the release is cut
# latest_release=$(cat "$SPLICE_ROOT/LATEST_RELEASE")
echo "release-line-0.4.4-snapshot.20250630.283.0.v06bbc9ce"
