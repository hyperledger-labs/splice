#!/usr/bin/env bash

set -eou pipefail
cd "$(dirname "$0")"

if [ "$#" -ne 1 ]; then
    echo "Usage: ./set-sdk.sh \$SDK_VERSION"
    exit 1
fi
SDK_VERSION=$1
echo "Setting SDK version to $SDK_VERSION"
find . -name daml.yaml -exec sed -i "s/^sdk-version:.*$/sdk-version: $SDK_VERSION/g" '{}' \;
(cd experiments/pay-with-cc && ./codegen.sh)
find . -name node_modules -prune -o -name daml.js -prune -o -name package.json -exec sed -i "s|@daml/g\(.*\): \".*\"|@daml/\1: \"$SDK_VERSION\"|g" '{}' \; -exec sh -c 'cd $(dirname $1) && npm install' sh '{}' \;
sed -i "s|\".*\" # sdk-version|\"$SDK_VERSION\" # sdk-version|g" .circleci/config.yml
