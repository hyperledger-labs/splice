#!/usr/bin/env bash

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
    scala -classpath "$BUNDLE/lib/coin-0.1.0-SNAPSHOT.jar" ./scripts/print-config.sc "$1"
fi
