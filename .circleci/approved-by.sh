#!/usr/bin/env bash

set -euo pipefail

function get_approver_login() {
    local user_id output

    output=$( curl -s --fail -X GET -u "${CIRCLECI_TOKEN}:" "https://circleci.com/api/v2/workflow/${CIRCLE_WORKFLOW_ID}/job" )

    user_id=$( jq -r '.items[] | select(.type == "approval") | .approved_by? ' <<<"$output" )

    curl -s --fail -X GET -u "${CIRCLECI_TOKEN}:" "https://circleci.com/api/v2/user/${user_id}" | jq -r .login
}

get_approver_login
