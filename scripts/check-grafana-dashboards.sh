#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# check-grafana-dashboards.sh

set -euo pipefail

usage() {
  echo "Usage: $0 [dashboard_file ...]"
  echo ""
  echo "Check and update Grafana dashboard JSON files to ensure they meet the required standards."
  echo
  echo "Positional arguments:"
  echo "  dashboard_file    One or more paths to Grafana dashboard JSON files to check."
  echo "                    If no files are provided, all dashboard files in the default directory will be checked."
}

check_grafana_dashboards() {
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  dashboard_dir="$script_dir/../cluster/pulumi/infra/grafana-dashboards"

  if [[ $# -gt 0 ]]; then
    dashboard_files=("$@")
  else
    IFS=$'\n' read -d '' -ra \
      dashboard_files < <(
        git ls-files -- "$dashboard_dir/*.json"
        printf '\0'
      )
  fi

  changed=false

  for dashboard_file in "${dashboard_files[@]}"; do
    dashboard_data=$(jq . "$dashboard_file")

    dashboard_data_modified=$(
      echo "$dashboard_data" | jq '
        .timezone = "" # Reset timezone to default
      '
    )

    if [[ $dashboard_data != "$dashboard_data_modified" ]]; then
      echo "Updating dashboard: $dashboard_file" >&2
      echo "$dashboard_data_modified" > "$dashboard_file"
      changed=true
    fi
  done

  if [[ $changed == true ]]; then
    if [[ ${CI-} == true ]]; then
      echo "ERROR: Some Grafana dashboards require changes." >&2
      git diff --color=always "$dashboard_dir" >&2
      echo "ERROR: Please run '$0' locally and commit the changes." >&2
    fi

    return 1
  else
    echo "INFO: All Grafana dashboards are up to date." >&2
    return 0
  fi
}

main() {
  if [[ $# -gt 0 && ( $1 == "-h" || $1 == "--help" ) ]]; then
    usage
    exit 0
  fi

  check_grafana_dashboards "$@"
}

main "$@"
