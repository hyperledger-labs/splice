#!/usr/bin/env bash

set -eou pipefail

function usage() {
  echo "Usage: ./scripts/start-backend-for-local-frontend-testing.sh <flags>"
  echo "Flags:"
  echo "  -h          display this help message"
  echo "  -s          skip bundle"
}

skip_bundle=0

while getopts "hdap:c:wsbtfg" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    s)
      skip_bundle=1
      echo "skip sbt --batch bundle"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done


INPUT_CONFIG="./apps/app/src/test/resources/minimal-topology.conf"
OUTPUT_CONFIG=$(mktemp)
trap 'rm -f "${OUTPUT_CONFIG}"' 0 2 3 15

if [ $skip_bundle -eq 0 ]; then
  echo "Running sbt bundle for an up-to-date config file definition"
  sbt --batch bundle
fi

echo "Generating config file ${OUTPUT_CONFIG} with self-signed tokens"
scala -classpath "$BUNDLE/lib/cn-node-0.1.0-SNAPSHOT.jar" ./scripts/transform-config.sc "useSelfSignedTokensForLedgerApiAuth" "${INPUT_CONFIG}" "${OUTPUT_CONFIG}"

echo "Starting Canton Network apps for local frontend testing"
cn-node --config "${OUTPUT_CONFIG}" --bootstrap ./apps/splitwell/frontend/bootstrap-minimal.sc --log-level-canton=DEBUG --log-encoder json --log-file-name log/cn-node_local_frontend_testing.clog
