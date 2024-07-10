#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Kills the entire process tree of the processes corresponding to
# the PIDs given in the argument.


function kill_process_tree() {
  local ppid

  for ppid in "$@"; do
    local cpids
    readarray -t cpids < <(pgrep -P "$ppid" || true)

    if [[ ${#cpids[@]} -gt 0 ]]; then
      kill_process_tree "${cpids[@]}" || true
    fi

    kill -9 "$ppid" 2>/dev/null || true
  done
}

