#!/usr/bin/env bash

set -eou pipefail

INPUT_CONFIG="./apps/app/src/test/resources/simple-topology-canton.conf"
OUTPUT_CONFIG=$(mktemp)
trap "rm -f ${OUTPUT_CONFIG}" 0 2 3 15

echo "Running sbt bundle for an up-to-date config file definition"
echo "If you wish to skip this step, comment out the corresponding line"
sbt --batch bundle

echo "Generating config file ${OUTPUT_CONFIG}"
scala -classpath /Users/robert/the-real-canton-coin/apps/app/target/release/coin/lib/coin-0.1.0-SNAPSHOT.jar ./scripts/transform-config.sc "remoteCantonConfigWithAdminTokens" "${INPUT_CONFIG}" "${OUTPUT_CONFIG}"

echo "Starting Canton console"
canton --log-level-canton=TRACE -c "${OUTPUT_CONFIG}"
