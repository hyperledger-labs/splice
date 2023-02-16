#!/usr/bin/env bash

set -eou pipefail

echo "Getting onboarding secret from SV1..."
curl -sSfL -X POST http://sv-app-1.sv-app-1:6014/devnet/onboard/validator/prepare  | xargs -I _ sed -i 's#PLACEHOLDER#_#' /app/app.conf
