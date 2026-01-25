#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

SRC=$1
DEST=$2

mkdir -p "$DEST"
cp -r "$SRC"/* "$DEST"

for i in $(seq 1 4); do
  sed -i "s/0.0.0.0:2665/0.0.0.0:266$i/g" "$DEST/sv$i/config/config.toml"
  sed -i 's/sv1:26656/127.0.0.1:26616/g' "$DEST/sv$i/config/config.toml"
  sed -i "s/sv$i:26656/127.0.0.1:266${i}6/g" "$DEST/sv$i/config/config.toml"
  cp "$DEST/genesis-multi-validator.json" "$DEST/sv$i/config/genesis.json"
done

sed -i 's/sv1:26656/127.0.0.1:26616/g' "$DEST/sv2Local/config/config.toml"
sed -i "s/sv2:26656/127.0.0.1:26656/g" "$DEST/sv2Local/config/config.toml"
cp "$DEST/genesis-multi-validator.json" "$DEST/sv2Local/config/genesis.json"
