#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source "${SCRIPT_DIR}/costs-common.sh"
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.cluster_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1h, mean(val())
            ")

_info "Total logging volume over the last hour:"
echo "$data" | jq -r '.timeSeriesData[] | ((.pointData[0].values[0].doubleValue / 1000000.0) | tostring) + " MBytes"'

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.namespace_name, resource.container_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1h, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 containers per namespace by logging volume over the last hour:"
echo "$data" | jq -r '.timeSeriesData | map({"namespace/container": (.labelValues[0].stringValue + "/" + .labelValues[1].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["namespace/container"] + ": " + (.value / 1000000.0 | tostring) + " MBytes") | .[]'

data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.container_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1h, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 containers by logging volume over the last hour:"
echo "$data" | jq -r '.timeSeriesData | map({"container": (.labelValues[0].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["container"] + ": " + (.value / 1000000.0 | tostring) + " MBytes") | .[]'


data=$(get_metrics "fetch k8s_container
              | filter resource.cluster_name =~ \"$CLUSTER\"
              | metric logging.googleapis.com/byte_count
              | align rate(1m)
              | every 1m
              | group_by [resource.namespace_name],
                   [value_byte_count_aggregate: aggregate(value.byte_count)]
              | group_by 1h, mean(val())
              | top 30
            ")

echo ""
_info "Top 30 namespaces by logging volume over the last hour:"
echo "$data" | jq -r '.timeSeriesData | map({"namespace": (.labelValues[0].stringValue), "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse | map(.["namespace"] + ": " + (.value / 1000000.0 | tostring) + " MBytes") | .[]'

