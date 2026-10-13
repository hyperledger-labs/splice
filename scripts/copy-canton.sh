#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -x

if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/copy-canton.sh path/to/canton-oss"
    exit 1
fi

# rsync '--include' options for a whole path; each ancestor is added so that rsync
# descends into the tree. Must precede matching exclusions
# because first rule wins
keep_includes() {
    local path="$1"
    local args=(--include "/$path")
    local dir
    dir="$(dirname "$path")"
    while [[ $dir != "." && $dir != "/" ]]; do
        # trailing / means only match directory name; leading / anchors to root
        # of rsync source
        args=(--include "/$dir/" "${args[@]}")
        dir="$(dirname "$dir")"
    done
    printf '%s\n' "${args[@]}"
}

# shellcheck disable=SC2046
rsync -av --delete \
    $(keep_includes community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml) \
    --exclude '*/src/test/**' \
    --exclude version.sbt --exclude community-build.sbt --exclude deployment --exclude project --exclude scripts --exclude .idea \
    --exclude=.github --exclude=.git --exclude=.gitmodules --exclude 'LICENSE*.txt' --exclude README.md --exclude demo --exclude '*/test/daml' \
    --exclude /daml --exclude daml-common-staging --exclude '*/ledger-common-dars' --exclude '*/daml/CantonExamples' \
    --exclude '*/wartremove/test/*' --exclude "*/ledger-api-bench-tool" \
    --exclude '.ci' --exclude '.circleci' --exclude '.hooks' --exclude 'contributing' --exclude 'docker' \
    --exclude 'docs-open' --exclude 'nix' --exclude 'performance' --exclude 'dashboards' --exclude 'release' \
    --exclude 'base/adjustable-clock' \
    --exclude 'base/contextualized-logging' --exclude 'base/crypto' \
    --exclude 'base/daml-jwt' --exclude 'base/daml-tls' \
    --exclude 'base/errors' --exclude 'base/util-external' \
    --exclude 'base/executors' \
    --exclude 'base/grpc-utils' \
    --exclude 'base/ledger-resources' \
    --exclude 'base/logging-entries' \
    --exclude 'base/nameof' \
    --exclude 'base/nonempty' \
    --exclude 'base/nonempty-cats' \
    --exclude 'base/observability/metrics' \
    --exclude 'base/observability/tracing' \
    --exclude 'base/ports' \
    --exclude 'base/resources' \
    --exclude 'base/resources-grpc' \
    --exclude 'base/resources-pekko' \
    --exclude 'base/rs-grpc-bridge' \
    --exclude 'base/rs-grpc-pekko' \
    --exclude 'base/scala-utils' \
    --exclude 'base/scalatest-utils' \
    --exclude 'base/test-evidence' \
    --exclude 'base/testing-utils' \
    --exclude 'base/timer-utils' \
    --exclude 'community/lib/Blake2b' \
    --exclude 'community/lib/google-common-protos-scala' \
    --exclude 'community/lib/magnolify' \
    --exclude 'community/lib/scalatest' \
    --exclude 'community/lib/slick' \
    --exclude 'community/lib/wartremover-annotations' \
    --exclude 'community/bindings-java' --exclude "community/transcode" \
    --exclude 'community/kms-driver-api' \
    --exclude 'community/ledger-api-scala' --exclude "**/ledger-api-proto" \
    --exclude '*/canton-community-app/test/scala/*/integration/tests' \
    --exclude 'community/ledger/ledger-api-core' \
    --exclude 'community/model-based-testing-drivers' \
    --exclude 'community/model-based-testing-generators' \
    --exclude 'community/model-based-testing-integration-tests' \
    --exclude 'community/aws-kms-driver' \
    --exclude 'community/conformance-testing' \
    --exclude 'community/daml-script-tests' \
    --exclude 'community/kms-driver-testing' \
    --exclude 'community/ledger-test-tool' \
    --exclude 'community/microbench' \
    --exclude 'community/mock-kms-driver' \
    --exclude 'community/performance-driver' \
    --exclude 'community/sequencer-driver-api-conformance-tests' \
    --exclude 'community/upgrading-integration-tests' \
    --exclude 'community/ledger/ledger-api-string-interning-benchmark' \
    --exclude 'community/ledger/ledger-api-tools' \
    --exclude 'community/ledger/ledger-json-client' \
    --exclude 'community/docs' \
    --exclude 'community/traffic-enforcement/api' \
    --exclude 'community/daml-lf/api-type-signature' \
    --exclude 'community/daml-lf/archive' \
    --exclude 'community/daml-lf/data' \
    --exclude 'community/daml-lf/data-bench' \
    --exclude 'community/daml-lf/data-tests' \
    --exclude 'community/daml-lf/encoder' \
    --exclude 'community/daml-lf/engine' \
    --exclude 'community/daml-lf/ide-ledger' \
    --exclude 'community/daml-lf/interpreter' \
    --exclude 'community/daml-lf/language' \
    --exclude 'community/daml-lf/ledger-api-value' \
    --exclude 'community/daml-lf/ledger-api-value-proto' \
    --exclude 'community/daml-lf/parser' \
    --exclude 'community/daml-lf/repl' \
    --exclude 'community/daml-lf/snapshot' \
    --exclude 'community/daml-lf/snapshot-proto' \
    --exclude 'community/daml-lf/spec' \
    --exclude 'community/daml-lf/stable-packages' \
    --exclude 'community/daml-lf/tests' \
    --exclude 'community/daml-lf/transaction' \
    --exclude 'community/daml-lf/transaction-tests' \
    --exclude 'community/daml-lf/upgrades-matrix' \
    --exclude 'community/daml-lf/upgrades-matrix-integration' \
    --exclude 'community/daml-lf/validation' \
    --exclude 'protobuf-continuity-check' \
    --exclude 'release-notes' \
    "$1/" \
    canton/
# remove any broken symlinks after the copy
find -L canton/ -type l -exec rm {} +

canton_bft_src="$1/community/app/src/pack/examples/13-observability/grafana/dashboards/Canton"
canton_bft_dest="cluster/pulumi/observability/grafana-dashboards/canton-bft"
rm -rf "$canton_bft_dest"
mkdir -p "$canton_bft_dest"
cp "$canton_bft_src"/*.json "$canton_bft_dest/"
