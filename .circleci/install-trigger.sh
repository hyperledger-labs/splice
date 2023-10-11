#!/usr/bin/env bash
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

TRIGGER_NAME=$(jq -c '.name' < "${TRIGGER_DEFINITION_FILE}")

if [[ -z "$TRIGGER_NAME" ]]; then
    echo "Error: Trigger definition file does not contain a trigger name"
    exit 1
fi

verb=POST
endpoint="https://circleci.com/api/v2/project/github/DACH-NY/canton-network-node/schedule"

existing_schedule=$(curl -s "$endpoint" \
    -H "Content-Type: application/json" \
    -H "circle-token: $CIRCLECI_TOKEN" | jq -c '.items[] | select(.name == $ARGS.positional[0])' --jsonargs "${TRIGGER_NAME}")

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
    -H "circle-token: $CIRCLECI_TOKEN"
