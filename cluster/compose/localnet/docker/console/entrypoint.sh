#!/bin/bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

generate_jwt() {
  local sub="$1"
  local aud="$2"
  jwt-cli encode hs256 --s unsafe --p '{"sub": "'"$sub"'", "aud": "'"$aud"'"}'
}

APP_PROVIDER_VALIDATOR_USER_TOKEN=$(generate_jwt "$AUTH_APP_PROVIDER_VALIDATOR_USER_NAME" "$AUTH_APP_PROVIDER_AUDIENCE")
export APP_PROVIDER_VALIDATOR_USER_TOKEN
APP_USER_VALIDATOR_USER_TOKEN=$(generate_jwt "$AUTH_APP_USER_VALIDATOR_USER_NAME" "$AUTH_APP_USER_AUDIENCE")
export APP_USER_VALIDATOR_USER_TOKEN
SV_VALIDATOR_USER_TOKEN=$(generate_jwt "$AUTH_SV_VALIDATOR_USER_NAME" "$AUTH_SV_AUDIENCE")
export SV_VALIDATOR_USER_TOKEN

# source all scripts from /app/pre-startup/on so that env variables exported by them are available in the current shell
for script in /app/pre-startup/on/*.sh; do
# shellcheck disable=SC1090
  [ -f "$script" ] && source "$script"
done
/app/bin/canton --no-tty -c /app/app.conf
