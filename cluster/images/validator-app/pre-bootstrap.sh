#!/usr/bin/env bash

set -eou pipefail

source /app/tools.sh

MAX_RETRY=100
n=0

SV_SPONSOR_URL="http://sv-app.sv-1:5014"
if [[ -n ${CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS:-} ]]; then
    SV_SPONSOR_URL="$CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS"
fi

ONBOARD_SECRET_URL="${SV_SPONSOR_URL}/devnet/onboard/validator/prepare"

json_log "Getting onboarding secret from SV (${ONBOARD_SECRET_URL})..." "pre-bootstrap.sh"

until [ $n -gt $MAX_RETRY ]; do

  if SECRET=$(curl -sSfL -X POST "${ONBOARD_SECRET_URL}" 2> /dev/null); then
    sed -i "s#PLACEHOLDER#$SECRET#" /app/app.conf
    break
  else
    json_log "Failed to get onboarding secret. Retrying in 1 second..." "pre-bootstrap.sh"
    n=$((n+1))
    sleep 1
  fi
  if [ $n -gt $MAX_RETRY ]; then
    json_log "Getting onboarding secret exceeded max retries" "pre-bootstrap.sh" >&2
    exit 1
  fi
done
