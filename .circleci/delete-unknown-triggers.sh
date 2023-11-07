#!/usr/bin/env bash
# This script calls the CircleCI API to delete scheduled triggers that are not defined in the repo as a JSON file.

# Add your personal API token to the CIRCLECI_TOKEN environment variable before running the script.
# https://circleci.com/docs/managing-api-tokens

set -euo pipefail

cci_api_all_schedules="https://circleci.com/api/v2/project/github/DACH-NY/canton-network-node/schedule"

cci_api_schedule="https://circleci.com/api/v2/schedule"

function is_remote_in_local {
    local remote_trigger_name="$1"

    for TRIGGER in "$REPO_ROOT"/.circleci/trigger-*.json
    do
        local_trigger_name=$(jq -r '.name' "${TRIGGER}")
        if [ "$remote_trigger_name" == "$local_trigger_name" ]; then
            return 0
        fi
    done
    return 1
}

function get_remote_trigger_names {
    local remote_triggers
    remote_triggers=$(curl -s "$cci_api_all_schedules" \
        -H "Content-Type: application/json" \
        -H "circle-token: $CIRCLECI_TOKEN" | jq -r '.items[] | .name')

    echo "$remote_triggers"
}

function get_remote_trigger_id_by_name {
    local trigger_name="$1"

    local trigger_id
    trigger_id=$(curl -s "$cci_api_all_schedules" \
        -H "Content-Type: application/json" \
        -H "circle-token: $CIRCLECI_TOKEN" | jq -r '.items[] | select(.name == $tn) | .id' --arg tn "$trigger_name")

    echo "$trigger_id"
}

function delete_remote_trigger {
    local remote_trigger_id="$1"
    local endpoint="$cci_api_schedule/$remote_trigger_id"

    curl -X DELETE "$endpoint" \
        -H "Content-Type: application/json" \
        -H "circle-token: $CIRCLECI_TOKEN"
}

function check_remote_triggers {
    local -a lines
    readarray -t lines <<< "$1"
    local i
    for (( i=0; i<${#lines[@]}; i++ )) ; do
        local remote_trigger_name="${lines[$i]}"

        set +e
        is_remote_in_local "$remote_trigger_name"
        local result=$?
        set -e

        if [[ $result -eq 1 ]]; then
            echo
            echo "Trigger (name: <$remote_trigger_name>) does not exist in the repo, deleting..."
            trigger_id=$(get_remote_trigger_id_by_name "$remote_trigger_name")

            echo "Deleting trigger by id: $trigger_id"
            delete_remote_trigger "$trigger_id"
        fi
    done
}

check_remote_triggers "$(get_remote_trigger_names)"
