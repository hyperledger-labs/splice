#!/usr/bin/env bash

set -eou pipefail

source /app/tools.sh

json_log "Getting onboarding secret from SV1..." "pre-bootstrap.sh"

MAX_RETRY=100
n=0

SV_SPONSOR_URL="http://sv-app.sv-1:5014"
if [[ -n ${CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS:-} && -n ${CN_APP_VALIDATOR_SV_SPONSOR_PORT:-} ]]; then
    SV_SPONSOR_URL="$CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS:$CN_APP_VALIDATOR_SV_SPONSOR_PORT"
fi
until [ $n -gt $MAX_RETRY ]; do
  if SECRET=$(curl -sSfL -X POST "${SV_SPONSOR_URL}/devnet/onboard/validator/prepare" 2> /dev/null); then
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
