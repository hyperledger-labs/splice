#!/usr/bin/env bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Support both "./lock-first-scratch.sh" and "source lock-first-scratch.sh / .lock-first-scratch.sh"
# The latter puts you in the scratch directory, so it's slightly more convenient.



script_is_not_sourced=0
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    script_is_not_sourced=1
fi

if (( script_is_not_sourced )); then
  set -eou pipefail
fi


scratches=(
  "scratchneta"
  "scratchnetb"
  "scratchnetc"
  "scratchnetd"
  "scratchnete"
)

successful=""

for scratch in "${scratches[@]}"; do
  echo "Attempting to lock $scratch..."
  cd "$SPLICE_ROOT/cluster/deployment/$scratch"
  locked_scratch="$scratch"
  direnv exec . cncluster lock && successful="true" && break
done

if [ -z "$successful" ]; then
  echo "Failed to lock any scratch."
  if (( script_is_not_sourced )) ; then
    exit 1
  fi
else
  echo "Successfully locked $locked_scratch."
  if (( script_is_not_sourced )) ; then
      echo "Run 'cd $SPLICE_ROOT/cluster/deployment/$locked_scratch'."
      echo "Tip: next time, if you run the script with 'source lock-first-scratch.sh', you will be put in the scratch directory directly."
  fi
fi
