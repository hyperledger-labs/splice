#!/usr/bin/env bash

set -eou pipefail
cd "$(dirname "$0")"

rm -rf daml.js
mkdir -p daml.js

ROOT=../../..

daml2ts $ROOT/apps/directory/daml/.daml/dist/directory-service-0.1.0.dar -o daml.js
