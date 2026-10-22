#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Asks every SV sequencer of the given synchronizer serial for its synchronizer parameters,
# prints the first answer in full, and then only answers that differ from it and SVs that failed.
# Read-only: one GET to scan, one gRPC call per sequencer.
#
# Usage: compare-sequencer-synchronizer-params.sh <scan-url> <serial>
#   e.g. compare-sequencer-synchronizer-params.sh https://scan.sv-1.example.com 1

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <scan-url> <serial>" >&2
  exit 1
fi
scan_url="${1%/}"
serial="$2"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# One line per sequencer: "<sv name><TAB><sequencer url>"
curl -sSf "$scan_url/api/scan/v0/dso-sequencers" |
  jq -r --argjson serial "$serial" \
    '.domainSequencers[].sequencers[] | select(.synchronizerSerial == $serial) | "\(.svName)\t\(.url)"' \
    >"$tmp/sequencers"

if [ ! -s "$tmp/sequencers" ]; then
  echo "Scan lists no sequencers for serial $serial" >&2
  exit 1
fi

reference=""
ok=0
differ=0
failed=0
while IFS=$'\t' read -r sv url; do
  host="${url#*://}"
  host="${host%/}"
  out="$tmp/$sv.out"
  printf 'Checking %s (%s:443) ... ' "$sv" "$host"
  if ! grpcurl "$host:443" com.digitalasset.canton.sequencer.api.v30.SequencerConnectService/GetSynchronizerParameters \
    </dev/null >"$out" 2>&1; then
    echo "FAILED:"
    cat "$out"
    failed=$((failed + 1))
  elif [ -z "$reference" ]; then
    reference="$sv"
    echo "OK, parameters:"
    cat "$out"
    ok=$((ok + 1))
  elif ! diff -q "$tmp/$reference.out" "$out" >/dev/null; then
    echo "DIFFERS from $reference:"
    diff "$tmp/$reference.out" "$out" || true
    differ=$((differ + 1))
  else
    echo "OK"
    ok=$((ok + 1))
  fi
done <"$tmp/sequencers"

echo "=== Summary: $ok identical, $differ differing, $failed failed ==="
