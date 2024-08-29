#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

job_name="$1"

approve_job() {
    local job_name="$1"
    local suffix="$2"

    echo "Sending approval for job $job_name for ${suffix}"

    # Fetch the approval ID for the specific job
    # shellcheck disable=SC2155
    local approval_id=$(curl -s --location --request GET "https://circleci.com/api/v2/workflow/$CIRCLE_WORKFLOW_ID/job" \
    --header "Circle-Token: ${CIRCLECI_TOKEN}" | jq -r ".items[] | select(.name == \"${job_name}_${suffix}\").approval_request_id")

    # If an approval ID is found, approve the job
    if [ -n "$approval_id" ]; then
        curl -s --location --request POST "https://circleci.com/api/v2/workflow/$CIRCLE_WORKFLOW_ID/approve/$approval_id" \
        --header "Circle-Token: ${CIRCLECI_TOKEN}"
        echo "Approval sent for job $job_name for ${suffix}."
    else
        echo "No approval request found for job $job_name for ${suffix}."
    fi
}

prod_clusters="devnet testnet mainnet"

for cluster in $prod_clusters
  do
    if git show "main:cluster/deployment/${cluster}/.envrc.vars" | grep "SPLICE_DEPLOYMENT_FLUX_REF" | grep "${TARGET_BRANCH}"; then
        approve_job "${job_name}" "${cluster}"
    elif [ "${TARGET_BRANCH}" = "main" ]; then
        approve_job "${job_name}" "deployment"
    else
        echo "TARGET_BRANCH (${TARGET_BRANCH}) does not match SPLICE_DEPLOYMENT_FLUX_REF for ${cluster}"
    fi
  done
