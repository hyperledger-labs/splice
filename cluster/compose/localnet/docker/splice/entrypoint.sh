#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# fetch onboarding secret from super-validator if none is provided. Only works in LocalNet and DevNet
if [[ "$APP_PROVIDER_PROFILE" = "on" && -z "${APP_PROVIDER_VALIDATOR_ONBOARDING_SECRET:-}" ]]; then
    echo "No onboarding secret provided. Attempting to fetch from super-validator via $ONBOARDING_SECRET_URL..."
    APP_PROVIDER_VALIDATOR_ONBOARDING_SECRET="$(curl -sfL -X POST "$ONBOARDING_SECRET_URL")"
    export APP_PROVIDER_VALIDATOR_ONBOARDING_SECRET
fi

if [[ "$APP_USER_PROFILE" = "on" && -z "${APP_USER_VALIDATOR_ONBOARDING_SECRET:-}" ]]; then
    echo "No onboarding secret provided. Attempting to fetch from super-validator via $ONBOARDING_SECRET_URL..."
    APP_USER_VALIDATOR_ONBOARDING_SECRET="$(curl -sfL -X POST "$ONBOARDING_SECRET_URL")"
    export APP_USER_VALIDATOR_ONBOARDING_SECRET
fi

exec /app/entrypoint.sh "$@"
