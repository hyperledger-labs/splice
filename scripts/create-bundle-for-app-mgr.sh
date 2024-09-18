#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# This script is used mostly for the app manager. If you are not sure you need this one,
# then most chances are you are looking for the `create-bundle.sh` script in the repo root.



# This is a shell script to allow for easily building custom bundles
# outside of tests, e.g, for manual testing.

set -eou pipefail

if [ "$#" -ne 4 ]
then
   >&2 echo "Expected 3 arguments but got $#"
   >&2 echo "Usage: \$REPO_ROOT/scripts/create-bundle.sh.sh path/to/dar path/to/release output.tar.gz"
   exit 1
fi

DAR="$1"
NAME="$2"
VERSION="$3"
OUTPUT="$4"

DIR="$NAME-$VERSION"

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
mkdir -p "$BUILD_DIR/$DIR/dars"

cp "$DAR" "$BUILD_DIR/$DIR/dars"
cat <<EOF > "$BUILD_DIR/$DIR/release.json"
{
  "version": "$VERSION"
}
EOF

mkdir -p "$(dirname "$OUTPUT")"

tar cvfz "$OUTPUT" -C "$BUILD_DIR" "$DIR"
