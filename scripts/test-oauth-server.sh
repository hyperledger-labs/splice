#!/usr/bin/env bash

set -eou pipefail

cd "$REPO_ROOT"
cd ./scripts/test-oauth-server/

node ./src/index.mjs "$@"
