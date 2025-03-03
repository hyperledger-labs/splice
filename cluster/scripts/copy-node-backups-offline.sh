#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

before_date=${1:-$(date +%Y-%m-%d)}
# We're only interested in sv-1 for mainnet, so we're hardcoding it here
namespace="sv-1"

BACKUPS_BUCKET_NAME="cn-mainnet-backups"


_info "Finding latest backup before $before_date..."
# TODO(#17852): for now, the instance ID is hardcoded to the participant from before the migration to ZRH
run_id=$(gcloud sql backups list --instance sv-1-participant-0-pg-fc951b4 --format "value(description)" --filter "description:* and endTime < ${before_date} and status = SUCCESSFUL and type = ON_DEMAND" --sort-by=~endTime --limit 1)

TARGET_DIR="gs://$BACKUPS_BUCKET_NAME/$GCP_CLUSTER_BASENAME/$run_id"

_info "Found backup: $run_id"

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

if [ $# -ne 1 ]; then
  _error "Usage: $0 <backup_run_id>"
  exit 1
fi


_info "Finding backups for run $run_id..."
function find_cloudsql_backups() {
  for instance in $(gcloud sql instances list --format "value(name)" --filter "labels.cluster = $GCP_CLUSTER_BASENAME AND name ~ ${namespace}.*"); do
    echo "Searching for backups in instance $instance..." >&2

    backup=$(gcloud sql backups list --instance "$instance" --format json --filter "description = $run_id" --format="value(id)");
    if [ -n "$backup" ]; then
      echo "$instance $backup";
    fi;
  done
}

cloudsql_backups=$(find_cloudsql_backups)
if [ "$(wc -l <<< "$cloudsql_backups")" -ne 4 ]; then
  _error_msg "Unexepcted number of backups found. Expected 4, found $(wc -l <<< "$cloudsql_backups"):"
  echo "$cloudsql_backups"
  exit 1
fi

pvc_snapshot=$(kubectl get volumesnapshot -n "${namespace}" --output name | grep "$run_id" | sed 's/volumesnapshot\.snapshot\.storage\.k8s\.io\///')

if [  "$(wc -l <<< "$pvc_snapshot")" -ne 1 ]; then
  _error_msg "Unexepcted number of PVC snapshots found. Expected 1, found $(wc -l <<< "$pvc_snapshot"):"
  echo "$pvc_snapshot"
  exit 1
fi

_info "Found cloudsql backups:"
while IFS= read -r line; do
  instance=$(echo "$line" | cut -d ' ' -f 1)
  backup_id=$(echo "$line" | cut -d ' ' -f 2)
  _info "Instance: $instance, Backup ID: $backup_id"
  "${SCRIPT_DIR}/copy-cloudsql-backup-offline.sh" "$instance" "$backup_id" "$TARGET_DIR" "$BACKUPS_BUCKET_NAME" &
done <<< "$cloudsql_backups"

_info "Found PVC snapshot: $pvc_snapshot"
"${SCRIPT_DIR}/copy-pvc-snapshot-offline.sh" "$pvc_snapshot" "$namespace" "$TARGET_DIR" &

wait

cat <<EOF | gcloud storage cp - "${TARGET_DIR}/backup-${run_id}.json"
{
  "before_date": "$before_date",
  "run_id": "$run_id",
  "copy_script_commit": "$(git rev-parse HEAD)",
  "git_repo": "$(git remote get-url origin)",
  "notes": "This clone of DA-2 MainNet backups was created for archiving purposes, \
by running the copy-node-backups-offline.sh script from the <git_repo> repo, \
at the <copy_script_commit> commit, with the <before_date> argument. \
It copied the backups with <run_id> (which is a UNIX timestamp of the time in which the
backups were originally taken)."
}
EOF
