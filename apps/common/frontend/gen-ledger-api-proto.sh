#!/usr/bin/env bash

set -eou pipefail

SDK_VERSION=2.4.0-snapshot.20220801.10312.0.d2c7be9d

# Generate protobuf sources for ledger API proto files.

rm -rf src/com/daml/ledger
DIR=$(mktemp -d)
trap "rm -rf $DIR" EXIT

curl -sSL https://github.com/digital-asset/daml/releases/download/v$SDK_VERSION/protobufs-$SDK_VERSION.zip --output $DIR/protobufs.zip
unzip -q $DIR/protobufs.zip -d $DIR
mkdir $DIR/ts
# We only generate sources for the subset we use.
protoc -I $DIR/protos-$SDK_VERSION com/daml/ledger/api/v1/value.proto \
       --js_out=import_style=commonjs:$DIR/ts \
       --grpc-web_out=import_style=commonjs+dts,mode=grpcwebtext:$DIR/ts
cp -r $DIR/ts/* src/
