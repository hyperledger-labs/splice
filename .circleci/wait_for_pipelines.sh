#!/usr/bin/env bash
set -eou pipefail
PIPELINE_NUMBER="$1"
WORKFLOW_NAMES="$2"

fetch() {
    url=$1
    target=$2
    # Retry in case of network flakiness
    curl -sSL \
        --retry 60 \
        --retry-max-time 120 \
        --fail -X GET -u "$CIRCLECI_TOKEN:" -H "Content-Type: application/json" -o "${target}" "${url}"
}

pipeline_workflows_complete() {
  pipeline_id=$1
  if fetch "https://circleci.com/api/v2/pipeline/$pipeline_id/workflow" "/tmp/workflows.json"
  then
    RESULT=$(jq < /tmp/workflows.json '.items | map(.status) | all(. != "running")')
    if [[ $RESULT == "true" ]]
    then
        echo "Pipeline complete"
        return 0
    else
        echo "Pipeline still running"
        cat /tmp/workflows.json
        return 1
    fi
  else
    echo "Could not fetch pipeline status; assuming that pipeline is still running"
    return 1
  fi
}

START_TIME=$(date +%s)
MAX_TIMEOUT_MINUTES=480

wait_for_pipeline_to_complete() {
    pipeline_id=$1
    while ! pipeline_workflows_complete "$PIPELINE_ID"
    do
        echo "Pipeline still running, waiting ..."
        sleep 5
        CURRENT_TIME=$(date +%s)
        DIFF=$((CURRENT_TIME - START_TIME))
        # wait up to $MAX_TIMEOUT_MINUTES number of minutes
        MAX=$((MAX_TIMEOUT_MINUTES * 60))
        if [[ $DIFF -ge $MAX ]]; then
            echo "Waited for $MAX_TIMEOUT_MINUTES min, but previous pipeline never became available. Quitting."
            exit 1
        fi
    done
}

fetch "https://circleci.com/api/v2/project/gh/$CIRCLE_PROJECT_USERNAME/$CIRCLE_PROJECT_REPONAME/pipeline?branch=main" "/tmp/pipelines.json"
# The results are paginated but we are interested in handling conflicts with more or less concurrent jobs so we can reasonably assume that relevant jobs are
# wtihin the first page and not fetch later responses.
PREVIOUS_JOBS=$(jq -c < /tmp/pipelines.json ".items | map(select(.number < $PIPELINE_NUMBER)) | .[]")
while IFS= read -r JOB; do
    PIPELINE_NUMBER=$(jq <<< "$JOB" -r '.number')
    PIPELINE_ID=$(jq <<< "$JOB" -r '.id')
    echo "Checking pipeline $PIPELINE_NUMBER ($PIPELINE_ID) ..."
    fetch "https://circleci.com/api/v2/pipeline/$PIPELINE_ID/workflow" "/tmp/workflows.json"
    while IFS= read -r WORKFLOW; do
        WORKFLOW_NAME=$(jq -r <<< "$WORKFLOW" '.name')
        if [[ " ${WORKFLOW_NAMES} " == *" ${WORKFLOW_NAME} "* ]]; then
            echo "Pipeline contains workflow $WORKFLOW_NAME, waiting for pipeline to complete ..."
            wait_for_pipeline_to_complete "$PIPELINE_ID"
        else
            echo "Ignoring workflow $WORKFLOW_NAME"
        fi
    done <<< "$(jq -c < /tmp/workflows.json ".items | .[]")"
done <<< "$PREVIOUS_JOBS"
