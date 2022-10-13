#!/usr/bin/env bash

set -eou pipefail

# Setup that runs all the individual scripts + npm install in one go.

./copy-proto-sources.sh
./gen-ledger-api-proto.sh
