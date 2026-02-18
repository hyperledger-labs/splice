#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

RUN_ID=$(date +%s)

##### PVC Backup & Restore

function backup_pvc() {
  local description=$1
  local namespace=$2
  local pvc_name=$3
  local migration_id=$4

  local backupName="${pvc_name}-$RUN_ID"

  _info "Backing up PVC $description"

  kubectl apply -n "$namespace" -f - <<EOF
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshot
metadata:
  name: "$backupName"
  annotations:
    migrationId: "$migration_id"
spec:
  volumeSnapshotClassName: dev-vsc
  source:
    persistentVolumeClaimName: "$pvc_name"
EOF
}

function wait_for_pvc_backup() {
  local description=$1
  local namespace=$2
  local pvc_name=$3

  local backupName="${pvc_name}-$RUN_ID"

  local -i i=0

  _info "Waiting for $description PVC backup to complete..."
  while true; do
    ready=$(kubectl get volumesnapshot -n "$namespace" -o json "$backupName" | jq .status.readyToUse)
    if [ "$ready" == "true" ]; then
      _info "Backup of $description PVC ready!"
      break
    else
      (( i++ )) && (( i > 300 )) && {
        # remove the finalizers to allow fully deleting them
        kubectl patch -n "$namespace" volumesnapshot "$pvc_name" -p '{"metadata":{"finalizers": []}}' --type=merge
        kubectl delete volumesnapshot -n "$namespace" "$backupName";
        _error "Timed out waiting for backup of $description PVC";
      }
      sleep 5
      _info "still waiting..."
    fi
  done
}

function backup_pvc_postgres() {
  local description=$1
  local namespace=$2
  local instance=$3
  local migration_id=$4
  local hyperdisk_enabled=$5

  _info "** Backup up pvc-based postgres $description **"

  # Since we only have one replica, it's always 0.
  replica_index="0"
  local pvc_name
  if [ "$hyperdisk_enabled" = "true" ]; then
    pvc_name="pg-data-hd-$instance-$replica_index"
  else
    pvc_name="pg-data-$instance-$replica_index"
  fi
  backup_pvc "$description" "$namespace" "$pvc_name" "$migration_id"
}

#### CloudSQL Backup & Restore

function backup_cloudsql() {
  local description=$1
  local instance=$2
  local stack=$3
  MAX_RETRIES=20
  retry_count=0

  _info "** Backing up $description **"

  _info "Looking for $description db"

  db_id=$(get_cloudsql_id "$instance" "$stack")

  if [ -z "$db_id" ]; then
    _error "No CloudSQL instance $instance found"
  fi

  echo "Waiting for any operations on $instance to finish"

  # Wait for any existing operations to finish (to avoid e.g. conflicting with automated periodic backups)
  gcloud sql operations list --instance="$db_id" --filter='NOT status:done' --format='value(name)' | xargs -r gcloud sql operations wait

  echo "All operations finished"

  _info "Starting backup of $description db ($db_id)"
  until [ $retry_count -gt $MAX_RETRIES ]; do
    # disabling exit on error to allow for retries
    set +e
    output=$(gcloud sql backups create --instance "$db_id" --description "$RUN_ID" 2>&1)
    backup_exit_code=$?
    set -e

    if [ $backup_exit_code -ne 0 ]; then
      if [[ $output == *"another operation was already in progress"* ]]; then
        _error_msg "Backup failed due to another operation in progress, retrying: $output"
      else
        _error_msg "$output"
      fi
      retry_count=$((retry_count+1))
      sleep 30
    else
      echo "Backup succeeded"
      return 0
    fi

    if [ $retry_count -gt $MAX_RETRIES ]; then
      _error "Backup of $description db exceeded max retries"
      return 1
    fi
  done
}

function wait_for_cloudsql_backup() {
  local description=$1
  local instance=$2
  local stack=$3

  db_id=$(get_cloudsql_id "$instance" "$stack")

  local -i i=0

  _info "Waiting for $description db backup to complete for $db_id..."
  while true; do
    backup=$(gcloud sql backups list --instance "$db_id" --filter="description=\"$RUN_ID\"" --format=json)
    status=$(echo "$backup" | jq -r '.[].status')
    id=$(echo "$backup" | jq -r '.[].id')

    if [ "$status" == "SUCCESSFUL" ]; then
      _info "Backup of $description ready! Backup ID: $id "
      break
    else
      (( i++ ))&& (( i > 300 )) &&_error "Timed out waiting for backup of $description db"
      sleep 5
      _info "still waiting..."
    fi
  done
}

### Generic Postgres (Local or CloudSQL)

function backup_postgres() {
  local description=$1
  local namespace=$2
  local instance=$3
  local migration_id=$4
  local stack=$5
  local hyperdisk_enabled=$6

  local full_instance="$namespace-$instance"

  type=$(get_postgres_type "$full_instance" "$stack")

  if [ "$type" == "canton:network:postgres" ]; then
    backup_pvc_postgres "$description" "$namespace" "$instance" "$migration_id" "$hyperdisk_enabled"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    backup_cloudsql "$description" "$full_instance" "$stack"
  elif [ -z "$type" ]; then
    _error "No postgres instance $full_instance found. Is the cluster deployed with split DB instances?"
  else
    _error "Unknown postgres type: $type"
  fi
}

function wait_for_postgres_backup() {
  local description=$1
  local namespace=$2
  local instance=$3
  local migration_id=$4
  local stack=$5
  local hyperdisk_enabled=$6

  local full_instance="$namespace-$instance"

  type=$(get_postgres_type "$full_instance" "$stack")

  if [ "$type" == "canton:network:postgres" ]; then
    # Since we only have one replica, it's always 0.
    replica_index="0"
    local pvc_name
    if [ "$hyperdisk_enabled" = "true" ]; then
      pvc_name="pg-data-hd-$instance-$replica_index"
    else
      pvc_name="pg-data-$instance-$replica_index"
    fi
    wait_for_pvc_backup "$description" "$namespace" "$pvc_name"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    wait_for_cloudsql_backup "$description" "$full_instance" "$stack"
  else
    _error "Unknown postgres type: $type"
  fi

}

function backup_component() {
  local namespace=$1
  local component=$2
  local requested_component=$3
  local migration_id=$4
  local hyperdisk_enabled=$5

  local stack
  stack=$(get_stack_for_namespace_component "$namespace" "$component")

  if [ "$component" == "$requested_component" ] || [ -z "$requested_component" ]; then
    if [ "$component" == "cometbft-$migration_id" ]; then
      local cometbft_pvc_name
      if [ "$hyperdisk_enabled" = "true" ]; then
        cometbft_pvc_name="cometbft-migration-${migration_id}-hd-pvc"
      else
        cometbft_pvc_name="global-domain-${migration_id}-cometbft-cometbft-data"
      fi
      backup_pvc "cometBFT" "$namespace" "$cometbft_pvc_name" "$migration_id"
    else
      local db_name
      db_name=$(create_component_instance "$component" "$migration_id" "$namespace")
      SPLICE_SV=$namespace SPLICE_MIGRATION_ID=$migration_id backup_postgres "$component" "$namespace" "$db_name-pg" "$migration_id" "$stack" "$hyperdisk_enabled"
    fi
  else
    _info "Skipping backup of $component, not requested"
  fi
}

function wait_for_backup() {
  local namespace=$1
  local component=$2
  local requested_component=$3
  local migration_id=$4
  local hyperdisk_enabled=$5

  local stack
  stack=$(get_stack_for_namespace_component "$namespace" "$component")

  if [ "$component" == "$requested_component" ] || [ -z "$requested_component" ]; then
    if [ "$component" == "cometbft-$migration_id" ]; then
      local cometbft_pvc_name
      if [ "$hyperdisk_enabled" = "true" ]; then
        cometbft_pvc_name="cometbft-migration-${migration_id}-hd-pvc"
      else
        cometbft_pvc_name="global-domain-${migration_id}-cometbft-cometbft-data"
      fi
      wait_for_pvc_backup "cometBFT" "$namespace" "$cometbft_pvc_name"
    else
      instance=$(create_component_instance "$component" "$migration_id" "$namespace")
      wait_for_postgres_backup "$component" "$namespace" "$instance-pg" "$migration_id" "$stack" "$hyperdisk_enabled"
    fi
  else
    _info "Skipping waiting for backup of $component, not requested"
  fi
}

function usage() {
  echo "Usage: $0 <sv|validator> <namespace> <migration id> [<component_name>]"
}

function main() {
  if [ "$#" -lt 3 ]; then
      usage
      exit 1
  fi

  local namespace=$2
  local migration_id=$3
  local requested_component="${4:-}"

  # Get resolved config and extract hyperdisk support flag
  local config
  config=$(get_resolved_config)
  local hyperdisk_enabled
  hyperdisk_enabled=$(echo "$config" | yq '.cluster.hyperdiskSupport.enabled // false')

  # TODO(#9361): support multiple domains / non-default-ID'd ones
  if [ "$1" == "validator" ]; then
    _info "Backing up validator $namespace"
    backup_component "$namespace" "validator" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    wait_for_backup "$namespace" "validator" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    # CN apps must be strictly before participant, so we sync on apps before starting the participant backup
    backup_component "$namespace" "participant" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    wait_for_backup "$namespace" "participant" "$requested_component" "$migration_id" "$hyperdisk_enabled"
  elif [ "$1" == "sv" ]; then
    _info "Backing up SV node $namespace"

    backup_component "$namespace" "cn-apps" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    backup_component "$namespace" "mediator" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    backup_component "$namespace" "sequencer" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    backup_component "$namespace" "cometbft-$migration_id" "$requested_component" "$migration_id" "$hyperdisk_enabled"

    wait_for_backup "$namespace" "cn-apps" "$requested_component" "$migration_id" "$hyperdisk_enabled"

    # CN apps must be strictly before participant, so we sync on apps before starting the participant backup
    backup_component "$namespace" "participant" "$requested_component" "$migration_id" "$hyperdisk_enabled"

    wait_for_backup "$namespace" "participant" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    wait_for_backup "$namespace" "mediator" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    wait_for_backup "$namespace" "sequencer" "$requested_component" "$migration_id" "$hyperdisk_enabled"
    wait_for_backup "$namespace" "cometbft-$migration_id" "$requested_component" "$migration_id" "$hyperdisk_enabled"
  else
    usage
    exit 1
  fi

  _info "Completed all backups for namespace $namespace, RUN_ID = $RUN_ID"
}

main "$@"
