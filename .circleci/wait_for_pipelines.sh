#!/usr/bin/env bash
set -eou pipefail
PIPELINE_NUMBER="$1"
WORKFLOW_NAMES="$2"

pipeline_workflows_complete() {
  pipeline_id=$1
  if fetch_pages "https://circleci.com/api/v2/pipeline/$pipeline_id/workflow" "/tmp/workflows.json"
  then
    echo "workflows for pipeline $pipeline_id:"
    cat /tmp/workflows.json
    echo ""
    # https://circleci.com/docs/api/v2/index.html#operation/listWorkflowsByPipelineId
    # Valid statuses are "success" "running" "not_run" "failed" "error" "failing" "on_hold" "canceled" "unauthorized"
    # We want to wait until it has fully completed
    # which seems to mean not running or failing
    RESULT=$(jq < /tmp/workflows.json '.items | map(.status) | all(. != "running" and . != "failing")')
    if [[ $RESULT == "true" ]]
    then
        echo "Pipeline complete"
        return 0
    else
        echo "Pipeline still running"
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

get_url() {
    local url=$1
    # Retry in case of network flakiness
    curl -sSL \
        --retry 60 \
        --retry-max-time 120 \
        --fail -X GET -u "$CIRCLECI_TOKEN:" -H "Content-Type: application/json" "${url}"
}

write_pages() {
    local url=$1
    local output_file=$2
    local response
    local next_page_token
    response=$(get_url "$url")
    next_page_token=$(jq -r '.next_page_token' <<< "$response")
    while IFS= read -r page; do
        echo "$page" >> "$output_file"
    done <<< "$response"

    if [ "$next_page_token" != "null" ]; then
        next_page_url="${url}?page-token=${next_page_token}"
        write_pages "$next_page_url" "$output_file"
    fi
}

fetch_pages() {
    local url=$1
    local output_file=$2
    rm -f "$output_file" >/dev/null 2>&1
    write_pages "$url" "$output_file"
}

tmp_pipelines_file="/tmp/pipelines.json"
tmp_workflows_file="/tmp/workflows.json"
fetch_pages "https://circleci.com/api/v2/project/gh/$CIRCLE_PROJECT_USERNAME/$CIRCLE_PROJECT_REPONAME/pipeline?branch=main" "$tmp_pipelines_file"

PREVIOUS_JOBS=$(jq -c < $tmp_pipelines_file ".items | map(select(.number < $PIPELINE_NUMBER)) | .[] | { number: .number, id: .id}")
if [ "${#PREVIOUS_JOBS}" -gt 0 ]; then
    while IFS= read -r JOB; do
        PIPELINE_NUMBER=$(jq <<< "$JOB" -r '.number')
        PIPELINE_ID=$(jq <<< "$JOB" -r '.id')
        echo "Checking pipeline $PIPELINE_NUMBER ($PIPELINE_ID) ..."
        fetch_pages "https://circleci.com/api/v2/pipeline/$PIPELINE_ID/workflow" "$tmp_workflows_file"
        while IFS= read -r WORKFLOW; do
            WORKFLOW_NAME=$(jq -r <<< "$WORKFLOW" '.name')
            if [[ " ${WORKFLOW_NAMES} " == *" ${WORKFLOW_NAME} "* ]]; then
                echo "Pipeline contains workflow $WORKFLOW_NAME, waiting for pipeline to complete ..."
                wait_for_pipeline_to_complete "$PIPELINE_ID"
            else
                echo "Ignoring workflow $WORKFLOW_NAME"
            fi
        done <<< "$(jq -c < "$tmp_workflows_file" ".items | .[]")"
    done <<< "$PREVIOUS_JOBS"
else
    echo "No previous jobs found for pipeline $PIPELINE_NUMBER, failing!"
    exit 1
fi
