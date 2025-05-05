#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

function usage() {
  echo "Usage: ./scripts/start-backend-for-local-frontend-testing.sh <flags>"
  echo "Flags:"
  echo "  -h          display this help message"
  echo "  -s          skip bundle"
  echo "  -l          start backend with two super validators for local testing"
}

skip_bundle=0
topology="minimal-topology.conf"
bootstrapScript="bootstrap-minimal.sc"

while getopts "hdap:c:wsbtfgl" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    s)
      skip_bundle=1
      echo "skip sbt --batch bundle"
      ;;
    l)
      topology="minimal-topology-2svs.conf"
      bootstrapScript="bootstrap.sc"
      echo "deploying backend for sv1 and sv2"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done


INPUT_CONFIG="./apps/app/src/test/resources/$topology"
OUTPUT_CONFIG=$(mktemp)
trap 'rm -f "${OUTPUT_CONFIG}"' 0 2 3 15

if [ $skip_bundle -eq 0 ]; then
  echo "Running sbt bundle for an up-to-date config file definition"
  sbt --batch bundle
fi

echo "Generating config file ${OUTPUT_CONFIG} with self-signed tokens"
scala -classpath "$BUNDLE/lib/splice-node.jar" ./scripts/transform-config.sc "useSelfSignedTokensForLedgerApiAuth" "${INPUT_CONFIG}" "${OUTPUT_CONFIG}"

echo "Starting Canton Network apps for local frontend testing"
splice-node --config "${OUTPUT_CONFIG}" --bootstrap ./apps/splitwell/frontend/$bootstrapScript --log-level-canton=DEBUG --log-encoder json --log-file-name log/splice-node_local_frontend_testing.clog
