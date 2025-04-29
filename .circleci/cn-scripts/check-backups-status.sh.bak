#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

alert_threshold="${1}"
cluster="${2}"
migration_id="${3}"


snapshot_array=()

check_snapshot_status() {
  local latest_creationTimestamp="${1}"
  local alert_threshold="${2}"
  local snapshot_name="${3}"
  local msg="${4}"
  if [ -n "${latest_creationTimestamp}" ] && date -d "${latest_creationTimestamp}" >/dev/null 2>&1; then
    latest_creation_epoch=$(date -d "${latest_creationTimestamp}" +%s)
    last_epoch=$(date -d "${alert_threshold}" +%s)

    if [ "$latest_creation_epoch" -lt "$last_epoch" ]; then
      msg+=" is older than ${alert_threshold} with creationTimestamp: ${latest_creationTimestamp}."
      echo "${msg}"
      snapshot_array+=("$snapshot_name")
    else
      msg+=" is not older than ${alert_threshold} with creationTimestamp: ${latest_creationTimestamp}."
      echo "${msg}"
    fi
  else
    echo "No snapshot found yet."
  fi
}

cncluster activate

# check volume snapshot
latest_creationTimestamp=$(kubectl get volumesnapshot -A -o json | jq -r '.items | sort_by(.metadata.creationTimestamp) | last | .metadata.creationTimestamp')
volume_snapshot_name=$(kubectl get volumesnapshot -A -o json | jq -r '.items | sort_by(.metadata.creationTimestamp) | last | .spec.source.persistentVolumeClaimName')
msg="The latest backup of volume snapshot $volume_snapshot_name"

check_snapshot_status "${latest_creationTimestamp}" "${alert_threshold}" "${volume_snapshot_name}" "${msg}"

# check cloud sql backups

# backups to be excluded from the check
exclude_patterns=("splitwell-sw" "sv-apps" "sv-sequencer" "sv-participant" "sv-mediator" "validator-")
exclude_pattern=$(printf "|%s" "${exclude_patterns[@]}")
exclude_pattern="${exclude_pattern:1}"

instances=$(gcloud sql instances list --format="value(name)" --filter="labels.cluster=${cluster} AND (labels.migration_id=${migration_id} OR NOT labels.migration_id:*)" | grep -vE "^($exclude_pattern)")

for instance in $instances;
  do
    echo "Checking backup for instance: $instance"
    latest_creationTimestamp=$(gcloud sql backups list --instance="$instance" --format="value(window_start_time)" --sort-by="window_start_time" | tail -1)
    msg="The latest cloud sql backup of $instance"
    check_snapshot_status "${latest_creationTimestamp}" "${alert_threshold}" "$instance" "${msg}"
  done

# shellcheck disable=SC2145
echo "export snapshot_array=(${snapshot_array[@]})" > "tmp-snapshots.sh"
