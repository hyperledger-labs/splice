#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source "${SCRIPT_DIR}/costs-common.sh"
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

_info "As of June'24, the cost of each 1 MB/sec of logging is about \$4K/month"

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.cluster_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1w, mean(val())
            ")

_info "Average total logging rate in the last week:"
echo "$data" | jq -r '.timeSeriesData[] | ((.pointData[0].values[0].doubleValue / 1000000.0) | tostring) + " MBytes/Sec"'

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.namespace_name, resource.container_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1w, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 containers per namespace by average logging rate in the last week:"
echo "$data" | jq -r '.timeSeriesData | map({"namespace/container": (.labelValues[0].stringValue + "/" + .labelValues[1].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["namespace/container"] + ": " + (.value / 1000000.0 | tostring) + " MBytes/sec") | .[]'

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.container_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1w, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 containers by average logging rate in the last week:"
echo "$data" | jq -r '.timeSeriesData | map({"container": (.labelValues[0].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["container"] + ": " + (.value / 1000000.0 | tostring) + " MBytes/sec") | .[]'


data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.namespace_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1w, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 namespaces by average logging rate in the last week:"
echo "$data" | jq -r '.timeSeriesData | map({"namespace": (.labelValues[0].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["namespace"] + ": " + (.value / 1000000.0 | tostring) + " MBytes/sec") | .[]'

