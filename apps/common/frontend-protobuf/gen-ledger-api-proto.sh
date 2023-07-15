#!/usr/bin/env bash

set -eou pipefail

# Generate protobuf sources for ledger API proto files.

rm -rf src/com/daml/ledger
DIR=$(mktemp -d)
mkdir -p "$DIR/protos-$SDK_VERSION/com/daml/ledger/api/v1/"
trap 'rm -rf "$DIR"' EXIT

# We only generate sources for the subset we use.
cp "${DAML_PROTOBUFS}/protos-$SDK_VERSION/com/daml/ledger/api/v1/value.proto" "$DIR/protos-$SDK_VERSION/com/daml/ledger/api/v1/value.proto"
cp "${DAML_PROTOBUFS}/protos-$SDK_VERSION/com/daml/ledger/api/v1/contract_metadata.proto" "$DIR/protos-$SDK_VERSION/com/daml/ledger/api/v1/contract_metadata.proto"
chmod -R +w "$DIR"/*

mkdir "$DIR/ts"

readarray -t PROTOS < <(cd "$DIR/protos-$SDK_VERSION" && find . -name '*.proto' | sed 's|^\./||')
protoc -I "$DIR/protos-$SDK_VERSION" "${PROTOS[@]}" \
       --js_out="import_style=commonjs:$DIR/ts" \
       --grpc-web_out="import_style=commonjs+dts,mode=grpcwebtext:$DIR/ts"
cp -r "$DIR/ts/"* "${REPO_ROOT}/apps/common/frontend-protobuf/"
