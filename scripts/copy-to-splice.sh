#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <splice-dir>"
  exit 1
fi

SPLICE_DIR=$1

if [ ! -d "$SPLICE_DIR" ]; then
  echo "Error: $SPLICE_DIR is not a directory"
  exit 1
fi

function copy_dir() {
  local path=$1

  dir=$(dirname "$path")
  name=$(basename "$path")

  rsync -ah --delete -v "${REPO_ROOT}/${dir}/${name}" "${SPLICE_DIR}/${dir}" \
    --exclude-from=<(git -C "${REPO_ROOT}/${dir}/${name}" ls-files --exclude-standard -oi --directory)
}


copy_dir "apps"
copy_dir "canton"
copy_dir "daml"
copy_dir "scripts/scan-txlog"
copy_dir "cluster/images"
copy_dir "cluster/helm"
