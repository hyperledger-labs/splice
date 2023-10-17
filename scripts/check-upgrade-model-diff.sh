#!/usr/bin/env bash

set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

DIFF_DIR=$(mktemp -d)

trap 'rm -rf $DIFF_DIR' EXIT

./update-upgrade-model-diff.sh "$DIFF_DIR"

for file in ../daml/upgrade-diffs/*.diff; do
    echo "Checking $file"
    diff -u "$file" "$DIFF_DIR/$(basename "$file")"
done
