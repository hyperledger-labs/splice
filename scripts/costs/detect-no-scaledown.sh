#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source "${SCRIPT_DIR}/costs-common.sh"
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

data=$(get_metrics "{
                fetch k8s_node
                  | metric kubernetes.io/node/cpu/total_cores;
                fetch k8s_container
                  | metric kubernetes.io/container/cpu/request_cores
              }
              | filter (resource.cluster_name =~ \"$CLUSTER\")
              | align next_older(1m)
              | group_by [resource.cluster_name]
              | join
              | sub
              | group_by 1h, min(val())
              | group_by 1w, max(val())
            ")

cores=$(echo "$data" | jq -r '.timeSeriesData[] | ((.pointData[0].values[0].doubleValue))')

_info "In the past week, there was a period of an hour where the total CPU requests was lower than the available cores by ${cores}."
_info "If this number is relatively high (~25 cores seems to be a reasonable threshold), the auto-scaler seems to not scale things down properly"
_info "Hint: search for \"noScaleDown\" in Google logs"
