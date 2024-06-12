#!/usr/bin/env bash

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

if [ -z "${GCP_CLUSTER_BASENAME:-}" ]; then
  _error "GCP_CLUSTER_BASENAME is not set. Please run this script from a deployment directory."
fi

TOKEN=$(gcloud auth print-access-token)
CLUSTER=cn-${GCP_CLUSTER_BASENAME}net

data=$(curl -sSL --fail-with-body -d "
  {
    \"query\": 'fetch k8s_container
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
              | group_by 4w, min(val())
              | top 20
             '
  }" \
  -H "Authorization: Bearer $TOKEN" \
  --header "Content-Type: application/json" \
  -X POST "https://monitoring.googleapis.com/v3/projects/${CLOUDSDK_CORE_PROJECT}/timeSeries:query")

echo "Top 20 strictly over-provisioned (CPU requests not ever used) containers by CPU usage over the last 4 weeks:"

echo "$data" | jq '.timeSeriesData | map({"label": .labelValues[0].stringValue, "value": .pointData[0].values[0].doubleValue}) | sort_by(.value) | reverse'
