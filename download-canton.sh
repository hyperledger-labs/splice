#!/usr/bin/env bash
set -eou pipefail

SDK_VERSION=2.4.0-snapshot.20220801.10312.0.d2c7be9d
CANTON_VERSION=20220802

if [ ! -d canton-release ]; then
    echo "No canton release in canton-release, downloading"
    mkdir canton-release
    curl -sSL https://github.com/digital-asset/daml/releases/download/v${SDK_VERSION}/canton-open-source-${CANTON_VERSION}.tar.gz | tar xz --strip-components=1 -C canton-release
fi
