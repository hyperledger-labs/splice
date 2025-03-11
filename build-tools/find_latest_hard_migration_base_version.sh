#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# On most days this script does the same thing as `build-tools/find_latest_base_version.sh`, which is authoritative for regular upgrades.
# Regular upgrades and hard migrations break under different conditions though, hence we use a separate script to separate the version selection logic more cleanly.

set -euo pipefail

# TODO(#18253): We temporarily hard-code this to a branch where we cherry-picked the deployment stack PR.
# once 0.3.16 is cut, this should be LATEST_RELEASE instead.
echo "0.3.15-deployment-stack"
