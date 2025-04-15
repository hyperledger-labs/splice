#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <target>"
  exit 1
fi
target="$1"
target_dir=$(dirname "$target")
target_file=$(basename "$target")
echo "Copying OpenAPI files to $target_dir"
mkdir -p "$target_dir"
abs_target_dir=$(realpath "$target_dir")

tmp_dir=$(mktemp -d)
mkdir -p "$tmp_dir/artifacts"
mkdir -p "$tmp_dir/openapi"
cp "$SPLICE_ROOT/apps/common/src/main/openapi/README.md" "$tmp_dir/openapi";

find "$SPLICE_ROOT" -ipath '*/openapi/*.yaml' -exec bash -c 'redocly bundle "$1" --output "$0"/openapi/$(basename "$1")' "$tmp_dir" {} \;

cd "$tmp_dir/openapi" || exit
tar -czvf openapi.tar.gz -- *
mv openapi.tar.gz "$abs_target_dir/$target_file"
