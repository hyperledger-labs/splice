#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail
cd "$(dirname "$0")"

if [ "$#" -ne 1 ]; then
    echo "Usage: ./set-sdk.sh \$SDK_VERSION"
    exit 1
fi
SDK_VERSION=$1
echo "Setting SDK version to $SDK_VERSION"
find . -name daml.yaml -exec sed -i "s/^sdk-version:.*$/sdk-version: $SDK_VERSION/g" '{}' \;
sed -i "s|\".*\" # sdk-version|\"$SDK_VERSION\" # sdk-version|g" .circleci/config.yml
