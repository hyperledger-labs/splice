#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

job_data=$( curl -s --fail --show-error -X GET -u "${CIRCLECI_TOKEN}:" "https://circleci.com/api/v2/workflow/${CIRCLE_WORKFLOW_ID}/job" )
printf "Job data: %s\n" "$job_data"
start=$(jq -r --arg job_name "${CIRCLE_JOB}" '.items[] | select(.name == $job_name) | .started_at' <<< "$job_data")
printf "Job start time: %s\n" "$start"
# somehow the start date is considered a secret by circleci, which causes it to be replaced with asterisks
# changing the date slightly (subtracting one second) avoids this
start_minus_one_second=$(date -u -d"$start -1 second" +"%Y-%m-%dT%H:%M:%S%:z")
printf "Job start time minus one second: %s\n" "$start_minus_one_second"
now_formatted=$(date -u +"%Y-%m-%dT%H:%M:%S%:z") # date defaults to now
printf "Current time formatted: %s\n" "$now_formatted"
GCP_CLUSTER_NAME="cn-${GCP_CLUSTER_BASENAME}net"
url="https://console.cloud.google.com/logs/query;startTime=$start_minus_one_second;endTime=$now_formatted;query=resource.labels.cluster_name%3D%22${GCP_CLUSTER_NAME}%22?project=$CLOUDSDK_CORE_PROJECT"
echo "Go to the following URL to see the logs since the job start in Google Cloud:"
echo "$url"
