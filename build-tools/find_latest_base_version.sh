#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

latest_release=$(cat "$SPLICE_ROOT/LATEST_RELEASE")
echo "release-line-${latest_release}"
