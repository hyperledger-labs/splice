#!/usr/bin/env bash

set -eou pipefail

source /app/tools.sh

json_log "Getting onboarding secret from SV1..." "pre-bootstrap.sh"

MAX_RETRY=100
n=0
until [ $n -gt $MAX_RETRY ]; do
  if SECRET=$(curl -sSfL -X POST http://sv-app.sv-1:6014/devnet/onboard/validator/prepare 2> /dev/null); then
    sed -i "s#PLACEHOLDER#$SECRET#" /app/app.conf
    break
  else
    json_log "Curl failed. Retrying in 1 second..." "pre-bootstrap.sh"
    n=$((n+1))
    sleep 1
  fi
  if [ $n -gt $MAX_RETRY ]; then
    json_log "Getting onboarding secret exceeded max retries" "pre-bootstrap.sh" >&2
    exit 1
  fi
done
