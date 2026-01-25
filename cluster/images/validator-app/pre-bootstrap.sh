#!/usr/bin/env bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

source /app/tools.sh

MAX_RETRY=100
n=0

if [[ -z ${SPLICE_APP_DEVNET:-} ]]; then
    json_log "Not running in devnet, relying on externally configured secret"
else
    if [[ -z ${SPLICE_APP_VALIDATOR_ONBOARDING_SECRET:-} ]]; then
        ONBOARD_SECRET_URL="${SPLICE_APP_VALIDATOR_SV_SPONSOR_ADDRESS}/api/sv/v0/devnet/onboard/validator/prepare"
        json_log "Getting onboarding secret from SV (${ONBOARD_SECRET_URL})..." "pre-bootstrap.sh"
        until [ $n -gt $MAX_RETRY ]; do
            if SECRET=$(wget --post-data="" -q -O - "${ONBOARD_SECRET_URL}"); then
                export SPLICE_APP_VALIDATOR_ONBOARDING_SECRET="$SECRET"
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
    else
        json_log "SPLICE_APP_VALIDATOR_ONBOARDING_SECRET already set, skipping fetching a new secret."
    fi
fi
