#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source "${SCRIPT_DIR}/costs-common.sh"
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

data=$(get_metrics "fetch k8s_node
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric kubernetes.io/node/cpu/allocatable_utilization
              | every 6h
              | group_by 6h,
                   [value_allocatable_utilization_mean:
                        max(value.allocatable_utilization)]
              | group_by [resource.cluster_name],
                   [value_allocatable_utilization_mean_aggregate:
                        aggregate(value_allocatable_utilization_mean)]
              | group_by 1w, min(val())
            ")

perc=$(echo "$data" | jq -r '.timeSeriesData[] | ((.pointData[0].values[0].doubleValue * 100) | tostring) + "%"')
_info "In the past week, there was a period of 6h where utilization was not over: ${perc}"

_info "If this number is relatively low, the auto-scaler seems to not scale things down properly"
_info "Hint: search for \"noScaleDown\" in Google logs"
