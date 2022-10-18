#!/usr/bin/env bash

set -eou pipefail
cd "$(dirname "$0")"

rm -rf daml.js
mkdir -p daml.js

ROOT=../../..

daml2ts \
    $ROOT/canton-coin/.daml/dist/canton-coin-0.1.0.dar \
    $ROOT/apps/directory/daml/.daml/dist/directory-service-0.1.0.dar \
    $ROOT/apps/wallet/daml/.daml/dist/wallet-0.1.0.dar \
    $ROOT/apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar \
    -o daml.js
