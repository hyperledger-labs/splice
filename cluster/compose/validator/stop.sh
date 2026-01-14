#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# issue a user friendly green informational message
function _info(){
  local first_line="INFO: "
  while read -r; do
    printf -- "\e[32;1m%s%s\e[0m\n" "${first_line:-     }" "${REPLY}"
    unset first_line
  done < <(echo -e "$@")
}

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

# export enough env variables to avoid annoying docker compose warnings about them missing
export TARGET_CLUSTER=""
export SPONSOR_SV_ADDRESS=""
export ONBOARDING_SECRET=""
export SCAN_ADDRESS=""
export MIGRATION_ID=""
export IMAGE_TAG=""
export SPLICE_APP_UI_NETWORK_NAME=""
export SPLICE_APP_UI_NETWORK_FAVICON_URL=""
export SPLICE_APP_UI_AMULET_NAME=""
export SPLICE_APP_UI_AMULET_NAME_ACRONYM=""
export SPLICE_APP_UI_NAME_SERVICE_NAME=""
export SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM=""
export VALIDATOR_PARTICIPANT_IDENTITIES_DUMP="v" # A non-empty dummy value, just to make the docker-compose spec valid
export VALIDATOR_NEW_PARTICIPANT_IDENTIFIER=""
export HOST_BIND_IP=""

# We include also compose-restore-from-id.yaml, so that if that was included in the start and created the init container,
# that container is also included in the down and removed
docker compose -f "$script_dir/compose.yaml" -f "$script_dir/compose-restore-from-id.yaml" down

_info "Validator stopped. Note that its data is persisted in the compose_postgres-splice volume, and will be reused if started again."
