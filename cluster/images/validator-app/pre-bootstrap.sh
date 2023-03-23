#!/usr/bin/env bash

set -eou pipefail

echo "Getting onboarding secret from SV1..."

MAX_RETRY=100
n=0
until [ $n -gt $MAX_RETRY ]; do
  if SECRET=$(curl -sSfL -X POST http://sv-app.sv-1:6014/devnet/onboard/validator/prepare); then
    sed -i "s#PLACEHOLDER#$SECRET#" /app/app.conf
    break
  else
    echo "Curl failed. Retrying in 1 second..."
    n=$((n+1))
    sleep 1
  fi
  if [ $n -gt $MAX_RETRY ]; then
    echo "Getting onboarding secret exceeded max retries" >&2
    exit 1
  fi
done
