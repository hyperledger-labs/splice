#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

#
# Stage per-operator Grafana dashboard folders for the release bundle.
#
# Produces:
#   <repo>/target/bundle-staging/grafana-dashboards/sv-grafana-dashboards/
#     -- all dashboards
#   <repo>/target/bundle-staging/grafana-dashboards/validator-grafana-dashboards/
#     -- only the files defined in validator-dashboards.yaml

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
src="$repo_root/cluster/pulumi/observability/grafana-dashboards"
manifest="$src/validator-dashboards.yaml"
out="$repo_root/target/bundle-staging/grafana-dashboards"

if [[ ! -d "$src" ]]; then
  echo "ERROR: source dir '$src' does not exist" >&2; exit 1
fi
if [[ ! -f "$manifest" ]]; then
  echo "ERROR: manifest '$manifest' does not exist" >&2; exit 1
fi
command -v yq >/dev/null || { echo "ERROR: yq is required" >&2; exit 1; }

sv_dest="$out/sv-grafana-dashboards"
val_dest="$out/validator-grafana-dashboards"
rm -rf "$sv_dest" "$val_dest"
mkdir -p "$sv_dest" "$val_dest"

# SV: include everything (JSON dashboards only)
( cd "$src" && find . -path ./gcp -prune -o -type f -name '*.json' -print0 ) \
  | while IFS= read -r -d '' rel; do
      rel=${rel#./}
      mkdir -p "$sv_dest/$(dirname "$rel")"
      cp "$src/$rel" "$sv_dest/$rel"
    done

# Validator: only what's in the manifest
shopt -s globstar nullglob
matched=0
while IFS= read -r pattern; do
  [[ -z "$pattern" ]] && continue
  for f in "$src"/$pattern; do
    [[ -f "$f" ]] || continue
    rel=${f#"$src"/}
    mkdir -p "$val_dest/$(dirname "$rel")"
    cp "$f" "$val_dest/$rel"
    matched=$((matched+1))
  done
done < <(yq -r '.include[]' "$manifest")

if [[ $matched -eq 0 ]]; then
  echo "ERROR: validator manifest matched 0 files" >&2; exit 1
fi

echo "Staged SV dashboards into $sv_dest"
echo "Staged validator dashboards into $val_dest ($matched files)"
