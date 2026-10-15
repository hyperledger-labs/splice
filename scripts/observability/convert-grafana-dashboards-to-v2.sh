#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

#
# Convert classic Grafana dashboard JSON files to the V2 resource format, in place.
#
# Uses Grafana's own converter: the dashboards are provisioned into a throwaway Grafana container and
# read back through the dashboard.grafana.app/v2 API. Files that are already in a Kubernetes resource
# format (have an `apiVersion`) are left untouched. Either all files are converted or none are.
#
# The converter resolves datasource references against the datasources of that Grafana instance; a
# query without a datasource is bound to the type of the default datasource. By default, a Prometheus
# datasource with uid `prometheus` is the default. Pass --datasources with a Grafana datasource
# provisioning file that matches your Grafana instance if your dashboards use other datasources.
#
# Requires: docker, curl, jq

set -euo pipefail

default_image="grafana/grafana:13.2.2"

usage() {
  cat <<EOF
Usage: $0 [--grafana-image IMAGE] [--datasources FILE] DIR

Convert all classic Grafana dashboard JSON files under DIR (recursively) to the V2 resource format.

Options:
  --grafana-image IMAGE   Grafana image used for the conversion (default: $default_image)
  --datasources FILE      Grafana datasource provisioning file describing the datasources the
                          dashboards use (default: a default Prometheus datasource with uid prometheus)
  -h, --help              Show this help
EOF
}

image="$default_image"
datasources=""
dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --grafana-image) image="$2"; shift 2 ;;
    --datasources) datasources="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "ERROR: unknown option $1" >&2; usage >&2; exit 1 ;;
    *)
      if [[ -n "$dir" ]]; then
        echo "ERROR: only one DIR is supported" >&2; exit 1
      fi
      dir="$1"; shift ;;
  esac
done
if [[ -z "$dir" ]]; then
  usage >&2; exit 1
fi
if [[ ! -d "$dir" ]]; then
  echo "ERROR: '$dir' is not a directory" >&2; exit 1
fi
if [[ -n "$datasources" && ! -f "$datasources" ]]; then
  echo "ERROR: '$datasources' is not a file" >&2; exit 1
fi
for tool in docker curl jq; do
  command -v "$tool" >/dev/null || { echo "ERROR: $tool is required" >&2; exit 1; }
done
dir=$(cd "$dir" && pwd)

work=$(mktemp -d)
container=""
cleanup() {
  if [[ -n "$container" ]]; then
    docker rm -f "$container" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# Stage only the classic dashboards, so that the container never sees the original directory and
# already converted dashboards don't take part.
staging="$work/dashboards"
output="$work/output"
mkdir -p "$staging" "$output"
# Lines of "<uid> <path relative to DIR>"; avoids associative arrays, which macOS' bash 3 lacks.
index="$work/index"
touch "$index"
failed=false
while IFS= read -r -d '' file; do
  rel=${file#"$dir"/}
  if ! jq -e 'type == "object"' "$file" >/dev/null 2>&1; then
    echo "ERROR: $rel: not a JSON object" >&2; failed=true; continue
  fi
  if jq -e 'has("apiVersion")' "$file" >/dev/null; then
    echo "Skipping $rel: already has an apiVersion" >&2; continue
  fi
  uid=$(jq -r '.uid // ""' "$file")
  if [[ -z "$uid" ]]; then
    echo "ERROR: $rel: no uid; set one so the converted dashboard keeps a stable uid" >&2
    failed=true; continue
  fi
  if [[ ! $uid =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "ERROR: $rel: unsupported uid '$uid'" >&2; failed=true; continue
  fi
  other=$(awk -v uid="$uid" '$1 == uid { sub(/^[^ ]+ /, ""); print }' "$index")
  if [[ -n "$other" ]]; then
    echo "ERROR: $rel: uid $uid is also used by $other" >&2; failed=true; continue
  fi
  echo "$uid $rel" >> "$index"
  mkdir -p "$staging/$(dirname "$rel")"
  cp "$file" "$staging/$rel"
done < <(find "$dir" -type f -name '*.json' -print0 | sort -z)
if [[ $failed == true ]]; then
  exit 1
fi
total=$(wc -l < "$index" | tr -d ' ')
if [[ $total -eq 0 ]]; then
  echo "INFO: No classic dashboards found under $dir" >&2; exit 0
fi

mkdir -p "$work/provisioning/dashboards" "$work/provisioning/datasources"
cat > "$work/provisioning/dashboards/dashboards.yaml" <<EOF
apiVersion: 1
providers:
  - name: convert
    type: file
    options:
      path: /dashboards
EOF
if [[ -n "$datasources" ]]; then
  cp "$datasources" "$work/provisioning/datasources/datasources.yaml"
else
  cat > "$work/provisioning/datasources/datasources.yaml" <<EOF
apiVersion: 1
datasources:
  - name: prometheus
    uid: prometheus
    type: prometheus
    url: http://localhost:9090
    isDefault: true
EOF
fi
# The container runs as a different user and must be able to read the copied files.
chmod -R a+rX "$work"

# Kept out of command lines so that other local users can't read it from the process list.
GF_SECURITY_ADMIN_PASSWORD=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
export GF_SECURITY_ADMIN_PASSWORD
echo "Starting $image to convert $total dashboards" >&2
# Files are copied into the container rather than bind-mounted, which also works with remote docker
# daemons and VMs that don't share the temp directory.
container=$(docker create \
  -p 127.0.0.1::3000 \
  -e GF_SECURITY_ADMIN_PASSWORD \
  -e GF_ANALYTICS_REPORTING_ENABLED=false \
  -e GF_ANALYTICS_CHECK_FOR_UPDATES=false \
  -e GF_PLUGINS_PREINSTALL_DISABLED=true \
  "$image")
docker cp "$work/provisioning/." "$container:/etc/grafana/provisioning" >/dev/null
docker cp "$staging" "$container:/dashboards" >/dev/null
docker start "$container" >/dev/null
port=$(docker port "$container" 3000/tcp | head -n 1 | sed 's/.*://')
url="http://127.0.0.1:$port"

api() {
  curl -sSf -K - "$url$1" <<<"user = \"admin:$GF_SECURITY_ADMIN_PASSWORD\""
}

# Wait until Grafana has provisioned every staged dashboard. Once Grafana reports that the initial
# provisioning is done, only allow a short grace period for the search index to catch up, so that
# dashboards Grafana rejected fail fast.
deadline=$((SECONDS + 180))
provisioned=0
while true; do
  if count=$(api '/api/search?type=dash-db&limit=5000' 2>/dev/null | jq length); then
    provisioned=$count
    if [[ $provisioned -ge $total ]]; then
      break
    fi
  fi
  if [[ $(docker inspect -f '{{.State.Running}}' "$container") != true ]]; then
    echo "ERROR: Grafana exited:" >&2
    docker logs "$container" 2>&1 | tail -n 20 >&2
    exit 1
  fi
  # Not grep -q: exiting early would make `docker logs` fail with SIGPIPE.
  if [[ $deadline -gt $((SECONDS + 20)) ]] &&
    docker logs "$container" 2>&1 | grep 'finished to provision dashboards' >/dev/null; then
    deadline=$((SECONDS + 20))
  fi
  if [[ $SECONDS -ge $deadline ]]; then
    echo "ERROR: Grafana provisioned only $provisioned of $total dashboards; Grafana errors:" >&2
    docker logs "$container" 2>&1 | grep 'logger=provisioning' | grep 'level=error' >&2 || true
    exit 1
  fi
  sleep 2
done

while read -r uid rel; do
  if ! dashboard=$(api "/apis/dashboard.grafana.app/v2/namespaces/default/dashboards/$uid"); then
    echo "ERROR: $rel: could not fetch dashboard $uid from Grafana" >&2; failed=true; continue
  fi
  if ! jq -e '(.status.conversion.failed // false | not) and (.spec.elements != null)' \
    <<<"$dashboard" >/dev/null; then
    echo "ERROR: $rel: conversion failed: $(jq -c '.status.conversion // .' <<<"$dashboard")" >&2
    failed=true; continue
  fi
  # A query bound to the built-in Grafana datasource although it didn't reference it means that the
  # converter couldn't resolve the datasource it refers to.
  unresolved=$(jq -r '[.. | objects | select(.kind == "DataQuery" and .group == "grafana"
      and ((.datasource.name // "") | IN("-- Grafana --", "grafana") | not))] | length' <<<"$dashboard")
  if [[ $unresolved -gt 0 ]]; then
    echo "ERROR: $rel: $unresolved queries could not be resolved to a datasource;" \
      "pass a default datasource and the ones your dashboards use with --datasources" >&2
    failed=true; continue
  fi
  source_path=$(jq -r '.metadata.annotations["grafana.app/sourcePath"] // ""' <<<"$dashboard")
  if [[ "$source_path" != "/dashboards/$rel" ]]; then
    echo "ERROR: $rel: Grafana loaded uid $uid from '$source_path'" >&2; failed=true; continue
  fi
  mkdir -p "$output/$(dirname "$rel")"
  jq '{apiVersion, kind, metadata: {name: .metadata.name}, spec}' <<<"$dashboard" > "$output/$rel"
done < "$index"
if [[ $failed == true ]]; then
  echo "ERROR: No files were changed" >&2
  exit 1
fi

while read -r _ rel; do
  cat "$output/$rel" > "$dir/$rel"
done < "$index"
echo "INFO: Converted $total dashboards" >&2
