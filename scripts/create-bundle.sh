#!/usr/bin/env bash

# This is a shell script to allow for easily building custom bundles
# outside of tests, e.g, for manual testing.

set -eou pipefail

if [ "$#" -ne 3 ]
then
   >&2 echo "Expected 3 arguments but got $#"
   >&2 echo "Usage: \$REPO_ROOT/scripts/create-bundle.sh.sh path/to/dar path/to/manifest output.tar.gz"
   exit 1
fi

DAR="$1"
MANIFEST="$2"
OUTPUT="$3"

NAME="$(jq -r '.name' < "$MANIFEST")"
VERSION="$(jq -r '.version' < "$MANIFEST")"

DIR="$NAME-$VERSION"

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
mkdir -p "$BUILD_DIR/$DIR/dars"

cp "$DAR" "$BUILD_DIR/$DIR/dars"
cp "$MANIFEST" "$BUILD_DIR/$DIR/manifest.json"

mkdir -p "$(dirname "$OUTPUT")"

tar cvfz "$OUTPUT" -C "$BUILD_DIR" "$DIR"
