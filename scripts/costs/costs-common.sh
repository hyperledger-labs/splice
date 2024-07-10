# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# shellcheck shell=bash



function get_metrics() {

  query=$1

  if [ -z "${GCP_CLUSTER_BASENAME:-}" ]; then
    _error "GCP_CLUSTER_BASENAME is not set. Please run this script from a deployment directory."
  fi

  TOKEN=$(gcloud auth print-access-token)

  curl -sSL --fail-with-body -d "{ \"query\": '$query' }" \
    -H "Authorization: Bearer $TOKEN" \
    --header "Content-Type: application/json" \
    -X POST "https://monitoring.googleapis.com/v3/projects/${CLOUDSDK_CORE_PROJECT}/timeSeries:query"

}
