#!/usr/bin/env bash

set -eou pipefail

INPUT_CONFIG="./apps/app/src/test/resources/simple-topology.conf"
OUTPUT_CONFIG=$(mktemp)
trap "rm -f ${OUTPUT_CONFIG}" 0 2 3 15

echo "Running sbt bundle for an up-to-date config file definition"
echo "If you wish to skip this step, comment out the corresponding line"
sbt bundle

echo "Generating config file ${OUTPUT_CONFIG} with admin tokens"
scala -classpath $BUNDLE/lib/coin-0.1.0-SNAPSHOT.jar ./scripts/transform-config.sc "useAdminAuthTokensForRemoteParticipants" "${INPUT_CONFIG}" "${OUTPUT_CONFIG}"

echo "Starting Canton Network apps for local frontend testing"
sbt "apps-app/runMain com.daml.network.CoinApp --config ${OUTPUT_CONFIG}"
