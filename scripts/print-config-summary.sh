#!/usr/bin/env bash

set -eou pipefail

echo "Running sbt bundle for an up-to-date config file definition"
echo "If you wish to skip this step, comment out the corresponding line"
sbt --batch bundle

echo "Printing port usage summary for simple-topology"
scala -classpath "$BUNDLE/lib/cn-node-0.1.0-SNAPSHOT.jar" ./scripts/print-config-summary.sc apps/app/src/test/resources/simple-topology.conf apps/app/src/test/resources/simple-topology-canton.conf
