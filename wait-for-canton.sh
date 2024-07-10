#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

function usage() {
  echo "Usage: ./start-canton.sh <flags>"
  echo "Flags:"
  echo "  -h               display this help message"
  echo "  -w               wait only for canton instance with wall clock time"
  echo "  -s               wait only for canton instance with simulated time"
}

wallclocktime=1
simtime=1

while getopts "hws" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    w)
      simtime=0
      echo "waiting only for canton with wall clock time"
      ;;
    s)
      wallclocktime=0
      echo "waiting only for canton with simulated time"
      ;;
    ?)
      ;;
  esac
done

# Wait for canton instance(s) to start within 5 minutes
timeout=300
start_time=$(date +%s)
while (( $(date +%s) - start_time < timeout )); do
    if { [ $wallclocktime -eq 1 ] && [ ! -f canton.tokens ]; } || { [ $simtime -eq 1 ] && [ ! -f canton-simtime.tokens ]; }; then
        remaining_time=$((timeout - ($(date +%s) - start_time)))
        echo "Waiting for Canton instance(s) to start (${remaining_time} seconds left)"
        sleep 1
    else
        echo "Canton instance(s) started"
        break
    fi
done

if (( $(date +%s) - start_time >= timeout )); then
    echo "Timeout: Canton instance(s) failed to start within $timeout seconds"
    exit 1
fi
