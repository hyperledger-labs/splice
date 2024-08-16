#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

( echo "Running sbt bundle for an up-to-date config file definition"
  echo "If you wish to skip this step, comment out the corresponding line"
  sbt --batch bundle
) >&2

if [[ $# -ne 1 ]]; then
    ( echo "$0: wrong number of arguments"
      echo ''
      echo "usage: $0 FILE"
      echo ''
      echo 'Prints a HOCON file in a canonical form (resolved, no comments, no origin comments) to stdout.'
      ) >&2
else
    echo "Printing resolved config for \`$1'" >&2
    scala -classpath "$BUNDLE/lib/splice-node.jar" ./scripts/print-config.sc "$1"
fi
