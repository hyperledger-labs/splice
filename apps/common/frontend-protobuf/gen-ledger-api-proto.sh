#!/usr/bin/env bash

set -eou pipefail

# Generate protobuf sources for ledger API proto files.

rm -rf src/com/daml/ledger
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

cp -r "${DAML_PROTOBUFS}"/* "$DIR"
chmod -R +w "$DIR"/*
# We only generate sources for the subset we use.
rm -rf "$DIR/protos-$SDK_VERSION/com/daml"/daml_lf_*

cp -r "${REPO_ROOT}/3rdparty/protobuf/google" "$DIR/protos-$SDK_VERSION/google"

mkdir -p "$DIR/protos-$SDK_VERSION/scalapb"
cp "${REPO_ROOT}/canton/research/app/target/protobuf_external/scalapb/scalapb.proto" "$DIR/protos-$SDK_VERSION/scalapb/scalapb.proto"

mkdir -p "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/crypto/v0"
mkdir -p "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/protocol/v0"
mkdir -p "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/v0"
cp "$CANTON/protobuf/community/com/digitalasset/canton/crypto/v0/crypto.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/crypto/v0/crypto.proto"
cp "$CANTON/protobuf/community/com/digitalasset/canton/protocol/v0/topology.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/protocol/v0/topology.proto"
cp "$CANTON/protobuf/community/com/digitalasset/canton/protocol/v0/sequencing.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/protocol/v0/sequencing.proto"
cp "$CANTON/protobuf/community/com/digitalasset/canton/v0/trace_context.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/v0/trace_context.proto"

mkdir -p "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/participant/protocol/v0/multidomain"
cp "$REPO_ROOT/canton/research/app/src/main/protobuf/com/digitalasset/canton/participant/protocol/v0/multidomain/transfer.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/participant/protocol/v0/multidomain/transfer.proto"
cp "$REPO_ROOT/canton/research/app/src/main/protobuf/com/digitalasset/canton/participant/protocol/v0/multidomain/state_service.proto" "$DIR/protos-$SDK_VERSION/com/digitalasset/canton/participant/protocol/v0/multidomain/state_service.proto"

mkdir "$DIR/ts"

readarray -t PROTOS < <(cd "$DIR/protos-$SDK_VERSION" && find . -name '*.proto' | sed 's|^\./||')
protoc -I "$DIR/protos-$SDK_VERSION" "${PROTOS[@]}" \
       --js_out="import_style=commonjs:$DIR/ts" \
       --grpc-web_out="import_style=commonjs+dts,mode=grpcwebtext:$DIR/ts"
cp -r "$DIR/ts/"* "${REPO_ROOT}/apps/common/frontend-protobuf/"
