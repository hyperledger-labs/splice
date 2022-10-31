#!/usr/bin/env bash

set -eou pipefail

# Generate protobuf sources for ledger API proto files.

rm -rf src/com/daml/ledger
DIR=$(mktemp -d)
trap "rm -rf $DIR" EXIT

cp -r ${DAML_PROTOBUFS}/* $DIR
chmod -R +w $DIR/*
cp -r ${REPO_ROOT}/3rdparty/protobuf/google $DIR/protos-$SDK_VERSION/google
mkdir $DIR/ts
rm -rf $DIR/protos-$SDK_VERSION/com/daml/daml_lf_1_14
rm -rf $DIR/protos-$SDK_VERSION/com/daml/daml_lf_dev
PROTOS=$(cd $DIR/protos-$SDK_VERSION && find . -name '*.proto' | sed 's|^\./||')
# We only generate sources for the subset we use.
protoc -I $DIR/protos-$SDK_VERSION $PROTOS \
       --js_out=import_style=commonjs:$DIR/ts \
       --grpc-web_out=import_style=commonjs+dts,mode=grpcwebtext:$DIR/ts
cp -r $DIR/ts/* ${REPO_ROOT}/apps/common/frontend-protobuf/
