#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# This script returns recent activity (comments only) on issues in the Flaky Tests milestone that are not labeled as "infrequent/no repro", or "blocked-on-upstream", or "workaround-inplace" and that are assigned to no one.

# The purpose is to help the monitoring team keep track of activity on issues that they may not be subscribed to and require attention.

OPTSTRING="l:h"
limit="5"

usage="Usage: $0 [-l <limit>] [-h]
    -l <limit>  Limit the number of results returned. Default is 5.
    -h          Display this help message."

set +u
while getopts ${OPTSTRING} opt; do
  case ${opt} in
    l)
        limit="${OPTARG}"
        ;;
    h)
        echo "$usage"
        exit 0
        ;;
    ?)
        echo "Invalid option: -${OPTARG}."
        exit 1
        ;;
  esac
done
set -u

gh api graphql --paginate --slurp -f query="$(cat "$SPLICE_ROOT/scripts/monitor-flaky-tests.graphql")" | \
    # Step 1: merge result objects of each separate page into 1 array
    jq '. | map(.data.repository.issues.nodes) | flatten' | \
    # Step 2: reshape & simplify result
    jq '. | map({
        number,
        title,
        url: .bodyUrl,
        labels: .labels.edges | map(.node.name) | join(", "),
        comments: .comments.totalCount,
        timestamp: (.comments.edges[-1].node.updatedAt // .createdAt)
    })' | \
    # Step 3: filter out issues based on labels
    jq '. | map(select(.labels | test("infrequent/no repro") | not))' | \
    jq '. | map(select(.labels | test("blocked-on-upstream") | not))' | \
    jq '. | map(select(.labels | test("stability:workaround-inplace") | not))' | \
    # Step 4: sort by last comment update, fallback to issue creation if 0 comments
    jq 'sort_by(.timestamp) | reverse' | \
    # Step 5: take latest "-l" results, or default 5
    jq 'limit('"$limit"'; .[])'
