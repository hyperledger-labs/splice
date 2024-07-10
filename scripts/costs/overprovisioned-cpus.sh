#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source "${SCRIPT_DIR}/costs-common.sh"
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | { metric kubernetes.io/container/cpu/request_cores;
                  metric kubernetes.io/container/cpu/core_usage_time
                  | rate
                  | every 1m
                }
              | align next_older(2m)
              | group_by [resource.container_name], .min()
              | join
              | sub
              | group_by 1w, min(val())
              | top 20
             ")

echo "Top 20 strictly over-provisioned (CPU requests not ever used) containers by CPU usage over the last week:"

echo "$data" | jq -r '.timeSeriesData | map({"label": .labelValues[0].stringValue, "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["label"] + ": " + (.value | tostring) + " cores") | .[]'
