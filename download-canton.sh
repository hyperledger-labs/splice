#!/usr/bin/env bash
set -eou pipefail

SDK_VERSION=2.4.0-snapshot.20220905.10544.0.a2ea3ce6
CANTON_VERSION=20220906

if [ ! -d canton-release ]; then
    echo "No canton release in canton-release, downloading"
    mkdir canton-release
    curl -sSL https://github.com/digital-asset/daml/releases/download/v${SDK_VERSION}/canton-open-source-${CANTON_VERSION}.tar.gz | tar xz --strip-components=1 -C canton-release
fi
