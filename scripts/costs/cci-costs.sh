#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# To obtain a CircleCI token, see: https://circleci.com/docs/managing-api-tokens/#creating-a-personal-api-token
data=$(curl -sSL --fail-with-body 'https://circleci.com/api/v2/insights/pages/github/DACH-NY/canton-network-node/summary?analytics-segmentation=web-ui-insights&reporting-window=last-90-days' \
       --header "Circle-Token: $CIRCLECI_TOKEN")

processed=$(echo "$data" | \
  jq '[.project_workflow_data[] | {workflow: .workflow_name, "credits_90_days": .metrics.total_credits_used, "credits_30_days": .metrics.total_credits_used | (./3), "usd/30 days": .metrics.total_credits_used | (./3) | (.*0.0006) , runs: .metrics.total_runs, "runs/30 days": .metrics.total_runs | (./3)}]')

total_month=$(echo "$processed" | jq 'map(.["usd/30 days"]) | add')

echo "Based on the last 90 days:"

echo ""
echo "Average total cost/month: $total_month USD"

echo ""
echo "Highest cost workflows/month:"
echo "$processed" | jq 'sort_by(-."usd/30 days") | .[0:5].[] | {workflow: .workflow, "usd/30 days": ."usd/30 days", runs: .runs}'

echo ""
echo "USD per build run:"
echo "$processed" | jq '.[] | select(.workflow == "build") | (."usd/30 days" / ."runs/30 days")'
