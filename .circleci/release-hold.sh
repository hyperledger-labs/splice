#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

job_name="$1"

approval_id=$(curl -s --location --request GET "https://circleci.com/api/v2/workflow/$CIRCLE_WORKFLOW_ID/job" --header "Circle-Token: ${CIRCLECI_TOKEN}" | jq -r ".items[]|select(.name == \"$job_name\").approval_request_id")

if [ -n "$approval_id" ]; then
  curl -s --location --request POST "https://circleci.com/api/v2/workflow/$CIRCLE_WORKFLOW_ID/approve/$approval_id" --header "Circle-Token: ${CIRCLECI_TOKEN}"
else
  echo "No approval request found for job $job_name."
fi