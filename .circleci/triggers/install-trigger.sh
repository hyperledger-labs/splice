#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# This script calls the CircleCI API to create / update the scheduled trigger.



# Add your personal API token to the CIRCLECI_TOKEN environment variable before running the script.
# https://circleci.com/docs/managing-api-tokens

set -euo pipefail

set +u
TRIGGER_DEFINITION_FILE=$1

echo "Processing $TRIGGER_DEFINITION_FILE"

if [[ -z $TRIGGER_DEFINITION_FILE ]]; then
    echo "Error: usage $0 trigger-definition-file"
    exit 1
fi

if [[ ! -f $TRIGGER_DEFINITION_FILE ]]; then
    echo "Error: Trigger definition file does not exist: $TRIGGER_DEFINITION_FILE"
    exit 1
fi


if [[ -z $CIRCLECI_TOKEN ]]; then
    echo "Error: \$CIRCLECI_TOKEN variable is not set."
    echo "Create and set your API token: https://circleci.com/docs/managing-api-tokens"
    exit 1
fi
set -u

BRANCH_VALUE=$(jq -r '.parameters.branch // empty' < "${TRIGGER_DEFINITION_FILE}")

case "$BRANCH_VALUE" in
    "DEVNET_RELEASE_BRANCH")
        CONFIG_YAML="$SPLICE_ROOT/cluster/deployment/devnet/config.yaml"
        ;;
    "TESTNET_RELEASE_BRANCH")
        CONFIG_YAML="$SPLICE_ROOT/cluster/deployment/testnet/config.yaml"
        ;;
    "MAINNET_RELEASE_BRANCH")
        CONFIG_YAML="$SPLICE_ROOT/cluster/deployment/mainnet/config.yaml"
        ;;
    *)
        CONFIG_YAML=""
        ;;
esac

if [[ -n "$CONFIG_YAML" ]]; then
    RELEASE_REFERENCE=$(yq eval '.synchronizerMigration.active.releaseReference' "$CONFIG_YAML")

    RELEASE_REFERENCE="${RELEASE_REFERENCE#refs/heads/}"

    echo "Found release reference: $RELEASE_REFERENCE"

    TMP_TRIGGER_FILE=$(mktemp)
    jq --arg branch "$RELEASE_REFERENCE" '.parameters.branch = $branch' "$TRIGGER_DEFINITION_FILE" > "$TMP_TRIGGER_FILE"

    echo "Updated trigger definition file with branch: $RELEASE_REFERENCE"
    TRIGGER_DEFINITION_FILE="$TMP_TRIGGER_FILE"
fi

TRIGGER_NAME=$(jq -c '.name' < "${TRIGGER_DEFINITION_FILE}")

if [[ -z "$TRIGGER_NAME" ]]; then
    echo "Error: Trigger definition file does not contain a trigger name"
    exit 1
fi

verb=POST
endpoint="https://circleci.com/api/v2/project/github/DACH-NY/canton-network-node/schedule"

existing_schedule=""
next_page_token=""

while true; do
  if [ -n "$next_page_token" ]; then
      paginated_endpoint="${endpoint}?page-token=${next_page_token}"
  else
      paginated_endpoint="$endpoint"
  fi

  response=$(curl -s "$paginated_endpoint" \
      -H "Content-Type: application/json" \
      -H "circle-token: $CIRCLECI_TOKEN")

  filtered_items=$(echo "$response" | jq -c '.items[] | select(.name == $ARGS.positional[0])' --jsonargs "${TRIGGER_NAME}")

  if [ -n "$filtered_items" ]; then
      existing_schedule="${existing_schedule}${filtered_items}"
  fi

  next_page_token=$(echo "$response" | jq -r '.next_page_token // empty')

  if [ -z "$next_page_token" ]; then
      break
  fi

  echo "Loading next page with next_page_token: $next_page_token"
done

echo "Existing schedules: $existing_schedule"

if [[ -n $existing_schedule ]]; then
    echo "Trigger ${TRIGGER_NAME} already exists... patching"
    schedule_id=$(echo "$existing_schedule" | jq -r .id)

    verb=PATCH
    endpoint="https://circleci.com/api/v2/schedule/$schedule_id"
else
    echo "Trigger ${TRIGGER_NAME} does not exist... creating"
fi

curl -X "$verb" "$endpoint" \
    -d "@$TRIGGER_DEFINITION_FILE" \
    -H "Content-Type: application/json" \
    -H "circle-token: $CIRCLECI_TOKEN" || {
      echo "Failed to create trigger"; exit 1;}
