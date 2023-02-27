#!/usr/bin/env bash

set -eou pipefail

echo "Getting onboarding secret from SV1..."

while true; do
  if SECRET=$(curl -sSfL -X POST http://sv-app.sv-1:6014/devnet/onboard/validator/prepare); then
    sed -i "s#PLACEHOLDER#$SECRET#" /app/app.conf
    break
  else
    echo "Curl failed. Retrying in 10 seconds..."
    sleep 10
  fi
done
