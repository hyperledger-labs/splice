#!/usr/bin/env bash
set -eou pipefail

CURRENT_PIPELINE_NUMBER="$1"
WORKFLOW_NAMES="$2"
# branch filter for pipelines; to provide a regex set BRANCH_FILTER_IS_REGEX to true.
# by default, we use the same branch as the current pipeline
BRANCH_FILTER="${3:-$CIRCLE_BRANCH}"
BRANCH_FILTER_IS_REGEX=${4:-false}
BRANCH_IGNORES=${5:-"^$"}  # do not execute on branches that match this pattern
# only fetching pipeline data up to seconds old defined here
# set to 0 by default which implies no pipeline age limit
MAX_AGE_SECONDS="${6:-0}"
# valid values for WAIT_OR_CANCEL:
# - wait - (default) wait for pipelines with workflows matching WORKFLOW_NAMES to complete
# - cancel_self - cancel current workflow if there exists a previously running pipeline with workflows matching WORKFLOW_NAMES
# - cancel_pipeline - cancel previously running pipelines with workflows matching WORKFLOW_NAMES
WAIT_OR_CANCEL="${7:-wait}"

START_TIME=$(date +%s)
MAX_TIMEOUT_MINUTES=480
BASE_CCI_API_URL="https://circleci.com/api/v2"

get_url() {
    local url="$1"
    # Retry in case of network flakiness
    curl -fsSL -X GET \
        --retry 60 \
        --retry-max-time 120 \
        -H "Circle-Token: ${CIRCLECI_TOKEN}" \
        -H "Content-Type: application/json" \
        "${url}"
}

write_pages() {
    local url="$1"
    local output_file="$2"
    local cutoff_date="$3"
    local response next_page_token new_url next_page_url earliest_date

    response=$(get_url "$url")
    next_page_token=$(jq -r '.next_page_token' <<<"$response")
    while IFS= read -r page; do
        echo "$page" >>"$output_file"
    done <<<"$response"
    if [ "$next_page_token" != "null" ]; then
        # shellcheck disable=SC2001
        new_url=$(sed 's/\(&\|?\)page-token=.*$//' <<<"$url")
        if [[ $new_url == *'?'* ]]; then
            next_page_url="${new_url}&page-token=${next_page_token}"
        else
            next_page_url="${new_url}?page-token=${next_page_token}"
        fi

        earliest_date=$(jq -r '.items | sort_by(.created_at)[0].created_at' <<<"$response")
        if [[ "$earliest_date" != "null" && -n "$cutoff_date" ]]; then
            if [[ "$earliest_date" > "$cutoff_date" ]]; then
                write_pages "$next_page_url" "$output_file" "$cutoff_date"
            else
                return 0
            fi
        else
            write_pages "$next_page_url" "$output_file" "$cutoff_date"
        fi
    fi
}

fetch_pages() {
    local url="$1"
    local output_file="$2"
    local cutoff_date="${3:-''}"
    rm -f "$output_file" >/dev/null 2>&1
    write_pages "$url" "$output_file" "$cutoff_date"
}

fetch_previous_pipelines_on_branch() {
    local branch_filter="$1"
    local branch_filter_is_regex="$2"
    local max_age_seconds="$3"
    local current_timestamp cutoff_timestamp cutoff_date filtered_pipelines
    if [ "$max_age_seconds" -gt 0 ]; then
        current_timestamp=$(date +%s)
        cutoff_timestamp=$((current_timestamp - max_age_seconds))
        cutoff_date=$(date -d "@$cutoff_timestamp" +%FT%T.000Z)
    else
        cutoff_date=""
    fi
    local tmp_pipelines_file="/tmp/pipelines.json"
    if [[ "$branch_filter_is_regex" == "true" ]]; then
        fetch_pages "$BASE_CCI_API_URL/project/gh/$CIRCLE_PROJECT_USERNAME/$CIRCLE_PROJECT_REPONAME/pipeline" "$tmp_pipelines_file" "$cutoff_date"
        # Note that .vcs.branch can be null if the workflow is triggered on a tag
        filtered_pipelines=$(jq -c ".items | map(select((.vcs.branch // \"\" | test(\"$branch_filter\")) and (.number|tonumber < $CURRENT_PIPELINE_NUMBER)))" <"$tmp_pipelines_file")
    else
        fetch_pages "$BASE_CCI_API_URL/project/gh/$CIRCLE_PROJECT_USERNAME/$CIRCLE_PROJECT_REPONAME/pipeline?branch=$branch_filter" "$tmp_pipelines_file" "$cutoff_date"
        filtered_pipelines=$(jq -c ".items | map(select(.number|tonumber < $CURRENT_PIPELINE_NUMBER))" <"$tmp_pipelines_file")
    fi
    if [[ -n "$cutoff_date" ]]; then
        filtered_pipelines=$(jq -c "map(select(.created_at >= \"$cutoff_date\"))" <<<"$filtered_pipelines")
    fi
    jq -c ".[] | {number: .number, branch: .vcs.branch, id: .id, created_at: .created_at}" <<<"$filtered_pipelines"
}

fetch_pipeline_workflows() {
    local pipeline_id="$1"
    local tmp_workflows_file="/tmp/workflows.json"
    fetch_pages "$BASE_CCI_API_URL/pipeline/$pipeline_id/workflow" "$tmp_workflows_file"
    jq -c ".items | .[]" <"$tmp_workflows_file"
}

pipeline_workflows_complete() {
    local pipeline_id="$1"
    local pipeline_workflows result

    pipeline_workflows=$(fetch_pipeline_workflows "$pipeline_id")
    echo "workflows for pipeline $pipeline_id:"
    echo "$pipeline_workflows"
    echo ""
    # https://circleci.com/docs/api/v2/index.html#operation/listWorkflowsByPipelineId
    # Valid statuses are "success" "running" "not_run" "failed" "error" "failing" "on_hold" "canceled" "unauthorized"
    # We want to wait until it has fully completed
    # which seems to mean not running or failing
    result=$(jq -s '. | all(.status != "running" and .status != "failing")' <<<"$pipeline_workflows")
    if [[ $result == "true" ]]; then
        echo "Pipeline complete"
        return 0
    else
        echo "Pipeline still running"
        return 1
    fi
}

wait_for_pipeline_to_complete() {
    local pipeline_id="$1"
    local current_time diff max
    while ! pipeline_workflows_complete "$pipeline_id"; do
        echo "Pipeline still running, waiting ..."
        sleep 5
        current_time=$(date +%s)
        diff=$((current_time - START_TIME))
        # wait up to $MAX_TIMEOUT_MINUTES number of minutes
        max=$((MAX_TIMEOUT_MINUTES * 60))
        if [[ $diff -ge $max ]]; then
            echo "Waited for $MAX_TIMEOUT_MINUTES min, but previous pipeline never became available. Quitting."
            exit 1
        fi
    done
}

cancel_workflow() {
    local workflow_id="$1"
    curl -fsSL -X POST \
        -H "Circle-Token: ${CIRCLECI_TOKEN}" \
        "$BASE_CCI_API_URL/workflow/$workflow_id/cancel"
}

cancel_self_if_pipeline_running() {
    local pipeline_id="$1"
    if ! pipeline_workflows_complete "$pipeline_id"; then
        cancel_workflow "$CIRCLE_WORKFLOW_ID"
    fi
}

cancel_all_pipeline_workflows() {
    local pipeline_id="$1"
    local pipeline_workflows workflow_id workflow_name workflow_status
    pipeline_workflows=$(fetch_pipeline_workflows "$pipeline_id")
    while IFS= read -r workflow; do
        workflow_id=$(jq -r '.id' <<<"$workflow")
        workflow_name=$(jq -r '.name' <<<"$workflow")
        workflow_status=$(jq -r '.status' <<<"$workflow")
        # See pipeline_workflows_complete() for the possible values for status
        # Here we cancel all non-terminated workflows to simulate the behaviour of CCI with
        # "Auto-cancel redundant workflows" set (https://circleci.com/docs/skip-build/#auto-cancel)
        if [[ "$workflow_status" == "running" || "$workflow_status" == "failing" || "$workflow_status" == "on_hold" ]]; then
            echo "Cancelling workflow $workflow_name ($workflow_id) with status $workflow_status"
            cancel_workflow "$workflow_id"
        fi
    done <<<"$pipeline_workflows"
}

run() {
    PREVIOUS_PIPELINES=$(fetch_previous_pipelines_on_branch "$BRANCH_FILTER" "$BRANCH_FILTER_IS_REGEX" "$MAX_AGE_SECONDS")
    if [ "${#PREVIOUS_PIPELINES}" -gt 0 ]; then
        while IFS= read -r PIPELINE; do
            PIPELINE_NUMBER=$(jq <<<"$PIPELINE" -r '.number')
            PIPELINE_ID=$(jq <<<"$PIPELINE" -r '.id')
            echo "Checking pipeline $PIPELINE_NUMBER ($PIPELINE_ID) ..."
            PIPELINE_WORKFLOWS=$(fetch_pipeline_workflows "$PIPELINE_ID")
            while IFS= read -r workflow; do
                WORKFLOW_NAME=$(jq -r '.name' <<<"$workflow")
                if [[ " ${WORKFLOW_NAMES} " == *" ${WORKFLOW_NAME} "* ]]; then
                    if [ "$WAIT_OR_CANCEL" = "cancel_self" ]; then
                        echo "Pipeline contains workflow $WORKFLOW_NAME, cancelling current workflow if pipeline is still running..."
                        cancel_self_if_pipeline_running "$PIPELINE_ID"
                    elif [ "$WAIT_OR_CANCEL" = "cancel_pipeline" ]; then
                        echo "Pipeline contains workflow $WORKFLOW_NAME, cancelling all workflows for pipeline ..."
                        cancel_all_pipeline_workflows "$PIPELINE_ID"
                    elif [ "$WAIT_OR_CANCEL" = "wait" ]; then
                        echo "Pipeline contains workflow $WORKFLOW_NAME, waiting for pipeline to complete ..."
                        wait_for_pipeline_to_complete "$PIPELINE_ID"
                    else
                        echo "Invalid value for WAIT_OR_CANCEL: $WAIT_OR_CANCEL"
                        exit 1
                    fi
                else
                    echo "Ignoring workflow $WORKFLOW_NAME"
                fi
            done <<<"$PIPELINE_WORKFLOWS"
        done <<<"$PREVIOUS_PIPELINES"
        if [[ "$MAX_AGE_SECONDS" -gt 0 ]]; then
            echo "Checked all pipelines created up to $((MAX_AGE_SECONDS / 3600)) hours ago before pipeline $CURRENT_PIPELINE_NUMBER"
        else
            echo "Checked all pipelines before pipeline $CURRENT_PIPELINE_NUMBER"
        fi
    else
        echo "No pipelines found before pipeline $CURRENT_PIPELINE_NUMBER, failing!"
        exit 1
    fi
}

if [[ "$BRANCH_FILTER_IS_REGEX" == "true" && "$MAX_AGE_SECONDS" -eq 0 ]]; then
    echo "Must provide a non-zero value for MAX_AGE_SECONDS if using a regex as the branch filter"
    exit 1
fi
if [[ "$CIRCLE_BRANCH" =~ $BRANCH_IGNORES ]]; then
    echo "Skipping since branch \"$CIRCLE_BRANCH\" matches ignore pattern $BRANCH_IGNORES"
    exit 0
fi

max_retries=5
counter=1
while [[ $counter -le max_retries ]]; do
    echo "Attempt $counter"

    # We can't just use something like `if run; then ... ; else ... ; fi`
    # because set -e gets disabled in the context of an if statement for Posix shells
    # and there is no way to enable it once again.
    #
    # Therefore, this trick of executing the command in the background and then waiting for it immediately
    # is required to obtain the correct exit code in the caller i.e. the exit code of the statement that failed
    # and not that of the last statement in the function definition. (https://unix.stackexchange.com/a/254676)
    run &
    if wait $!; then
        break
    else
        echo "Failed with error code $?. Retrying in 5 seconds..."
        echo
        sleep 5
        ((counter++))
    fi
done

if [[ $counter -gt $max_retries ]]; then
    echo "Failed after $max_retries attempts."
    exit 1
fi
