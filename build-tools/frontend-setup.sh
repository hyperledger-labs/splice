#!/usr/bin/env bash

set -eou pipefail

# Setup that runs all the individual scripts + npm install in one go.

./codegen.sh
./copy-proto-sources.sh
./gen-ledger-api-proto.sh
../../../build-tools/copy-common-ts.sh

../../../build-tools/npm-install.sh
