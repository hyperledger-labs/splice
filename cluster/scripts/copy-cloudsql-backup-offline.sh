#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

if [ $# -ne 4 ]; then
  _error "Usage: $0 <backup_instance> <backup_id> <target_dir> <bucket_name>"
  exit 1
fi

backup_instance=$1
backup_id=$2
target_dir=$3
bucket_name=$4
TEMP_DB_PROJECT="da-cn-shared"

function delete_instance() {
  local instance=$1
  if [ "$(gcloud sql instances list --filter "name = $instance" --format json | jq length)" -eq 0 ]; then
    _info "Instance $instance does not exist, nothing to delete"
    return
  fi
  # Despite the restore operations having completed, the delete sometimes still fails with
  # "Operation failed because another operation was already in progress", so we retry a few times
  for i in {1..5}; do
    _info "Deleting instance $instance (attempt $i)"
    if gcloud sql instances delete "$instance" --quiet --async; then
      return
    fi
    _warning "Failed to delete instance $instance. Retrying..."
    sleep 10
  done
  _error "Failed to delete instance $instance after 5 attempts"
}

function cleanup() {
  rc=$?
  if [ -n "${sa:-}" ]; then
    _info "Revoking permissions from the service account of the restored instance"
    gcloud storage buckets remove-iam-policy-binding "gs://${bucket_name}" \
      --member="serviceAccount:$sa" \
      --role=roles/storage.objectAdmin || true
  fi

  if [ -n "${restore_instance:-}" ]; then
    delete_instance "$restore_instance"
  fi
  exit $rc
}
trap cleanup EXIT

function wait_for_operation() {
  local id=$1
  local action=$2
  local i=0
  _info "Waiting for $action to complete (action id $id)..."
  while true; do
    status=$(gcloud sql operations describe "$id" --format "value(status)")
    if [ "$status" == "DONE" ]; then
      _info "$action completed successfully"
      break
    fi
    if [ "$status" != "PENDING" ] && [ "$status" != "RUNNING" ] ; then
      _error "Failed to $action. Current status: $status"
    fi
    if [ $i -ge 3600 ]; then
      _error "Timing out wait for $action after 10 hours. Current status: $status"
    fi
    i=$((i + 1))
    _info "Waiting for $action to complete... (current status: $status)"
    sleep 10
  done
}

instance_desc=$(gcloud sql instances describe "$backup_instance" --format json)
backup_desc=$(gcloud sql backups describe --instance "$backup_instance" "$backup_id" --format json)
run_id=$(echo "$backup_desc" | jq -r '.description')
_info "Restoring backup $backup_id of $backup_instance for run $run_id"

zone=$(echo "$instance_desc" | jq -r '.gceZone')

source_project="$CLOUDSDK_CORE_PROJECT"
export CLOUDSDK_CORE_PROJECT="$TEMP_DB_PROJECT"

restore_instance="backup-${backup_id}"
_info "Creating instance $restore_instance for restoring the backup"
id=$(gcloud sql instances create "$restore_instance" \
  --zone "$zone" \
  --database-version POSTGRES_14 \
  --tier db-custom-2-7680 \
  --async \
  --format "value(name)")

wait_for_operation "$id" "create instance $restore_instance"


_info "Restoring backup $backup_id to instance $restore_instance"
id=$(gcloud sql backups restore "$backup_id" \
  --restore-instance "$restore_instance" \
  --backup-instance "$backup_instance" \
  --backup-project "$source_project" \
  --quiet \
  --async \
  --format "value(name)")

wait_for_operation "$id" "restore backup $backup_id to instance $restore_instance"

_info "Fetching the service account of the restored instance"
sa=$(gcloud sql instances describe "$restore_instance" --format json | jq -r .serviceAccountEmailAddress)
i=0
while true; do
  _info "Granting permissions to the service account of the restored instance ($sa)"
  if gcloud storage buckets add-iam-policy-binding "gs://${bucket_name}" \
    --member="serviceAccount:$sa" \
    --role=roles/storage.objectAdmin; then
    break
  fi
  _warning "Failed to grant permissions to the service account $sa. Retrying..."
  i=$((i + 1))
  if [ $i -ge 5 ]; then
    _error "Failed to grant permissions to the service account $sa after 5 attempts"
  fi
done

all_dbs=$(gcloud sql databases list --instance "$restore_instance" --format json | jq -r '.[].name')

for db in $all_dbs; do
  filename="${target_dir}/${backup_instance}/${db}.sql.gz"
  _info "Exporting database $db to $filename"
  id=$(gcloud sql export sql "$restore_instance" "$filename" \
    --database "$db" \
    --quiet \
    --async \
    --format "value(name)")

  wait_for_operation "$id" "export database $db from $restore_instance to $filename"
done

# not calling cleanup() explicitly, as it's already called by the trap and calling it twice will print annoying warnings
