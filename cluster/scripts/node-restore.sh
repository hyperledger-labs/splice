#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

function component_to_deployments() {
  local -r component=$1
  local -r migration_id=$2
  if [[ "$component" == "sequencer" ]]; then
    echo "global-domain-$migration_id-sequencer"
  elif [[ "$component" == "mediator" ]]; then
    echo "global-domain-$migration_id-mediator"
  elif [[ "$component" == "participant" ]]; then
    echo "participant-$migration_id"
  elif [[ "$component" == "cometbft" ]]; then
    echo "global-domain-$migration_id-cometbft"
  elif [[ "$component" == "cn-apps" ]]; then
    echo "validator-app scan-app sv-app"
  elif [[ "$component" == "validator" ]]; then
    echo "validator-app"
  else
    _error "Unknown component: $component"
  fi
}

function create_pvc_from_snapshot() {
  local -r namespace=$1
  local -r snapshot_name=$2
  local -r pvc_name=$3
  local -r storage_class_name=$4

  vs=$(kubectl get volumesnapshot -n "$namespace" -o json "$snapshot_name")
  status=$(echo "$vs" | jq -r .status.readyToUse)
  if [ "$status" != "true" ]; then
    _error "Snapshot $snapshot_name is not ready to use"
  fi
  restore_size=$(echo "$vs" | jq -r .status.restoreSize)

  _info "Creating PVC $pvc_name from snapshot $snapshot_name"
  kubectl apply -n "$namespace" -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: "$pvc_name"
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: "$restore_size"
  storageClassName: "$storage_class_name"
  volumeMode: Filesystem
  dataSource:
    name: "$snapshot_name"
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
EOF
}

function restore_pvc_from_snapshot() {
  local -r namespace=$1
  local -r snapshot_name=$2
  local -r pvc_name=$3
  local -r storage_class_name=${4:-"standard-rwo"}

  _warning "This operation will delete pvc $pvc_name, and restore it from backup."
  _warning "Please consider backing up and/or cloning the DB instance before continuing."
  await_confirmation

  _info "Patching and deleting postgres PVC"
  local -r pv=$(kubectl get pvc -n "$namespace" "$pvc_name" -o=json | jq -r .spec.volumeName)
  # remove the finalizers on the pvc and pv to allow fully deleting them
  kubectl patch -n "$namespace" pvc "$pvc_name" -p '{"metadata":{"finalizers": []}}' --type=merge
  kubectl patch -n "$namespace" pv "$pv" -p '{"metadata":{"finalizers": []}}' --type=merge
  kubectl delete -n "$namespace" pvc "$pvc_name"

  _info "Recreating PVC from snapshot"
  create_pvc_from_snapshot "$namespace" "$snapshot_name" "$pvc_name" "$storage_class_name"
}

function down() {
  local -r namespace=$1
  local -r component=$2
  local -r migration_id=$3
  local -r deployment_names=$(component_to_deployments "$component" "$migration_id")

  for deployment_name in $deployment_names; do
    _info "Scaling down $component deployment $deployment_name"
    kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=0
  done
}

function wait_down() {
  local -r namespace=$1
  local -r component=$2
  local -r migration_id=$3
  local -r deployment_names=$(component_to_deployments "$component" "$migration_id")

  for deployment_name in $deployment_names; do
    _info "Waiting for all pods of $deployment_name to get deleted"
    kubectl wait pods -n "$namespace" -l "app=$deployment_name" --for delete --timeout=180s
  done
}

function up() {
  local -r namespace=$1
  local -r component=$2
  local -r migration_id=$3
  local -r deployment_names=$(component_to_deployments "$component" "$migration_id")

  for deployment_name in $deployment_names; do
    up_one_with_retries "$namespace" "$component" "$deployment_name"
  done
}

function up_one_with_retries() {
  local -r namespace=$1
  local -r component=$2
  local -r deployment_name=$3
  MAX_RETRIES=5
  retry_count=0

  until [ $retry_count -gt $MAX_RETRIES ]; do
    _info "Scaling up $component deployment $deployment_name"

    # disabling exit on error to allow for retries
    set +e
    output=$(kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=1 2>&1)
    restore_exit_code=$?
    set -e

    if [ $restore_exit_code -ne 0 ]; then
      _error_msg "$output"
      retry_count=$((retry_count+1))
      sleep 10
    else
      return 0
    fi

    if [ $retry_count -gt $MAX_RETRIES ]; then
      _error "Scaling up $component deployment $deployment_name exceeded max retries"
      return 1
    fi
  done
}

function restore_pvc_postgres() {
  local -r namespace=$1
  local -r component=$2
  local -r run_id=$3
  local -r hyperdisk_enabled=$4

  local template_name
  if [ "$hyperdisk_enabled" = "true" ]; then
    template_name="pg-data-hd"
  else
    template_name="pg-data"
  fi

  local -r ss_name="$component-pg"
  local -r pg_pod_name="$ss_name-0"
  local -r pvc_name="$template_name-$pg_pod_name"
  local -r snapshot_name="$pvc_name-$run_id"

  _info "Scaling down postgres StatefulSet"
  kubectl scale statefulset -n "$namespace" "$ss_name" --replicas=0

  restore_pvc_from_snapshot "$namespace" "$snapshot_name" "$pvc_name"

  _info "Scaling up postgres StatefulSet"
  kubectl scale statefulset -n "$namespace" "$ss_name" --replicas=1
}

function await_confirmation() {
  local ok=0
  while [ "$ok" -eq 0 ] && [ "$force" -eq 0 ]; do
    read -r -p "Type \"I know what I'm doing\" to continue: " confirmation

    if [ "$confirmation" != "I know what I'm doing" ]; then
      _warning "Are you sure you know what you're doing? Please try again."
    else
      ok=1
    fi
  done
}

function restore_cloudsql_postgres() {
  local -r namespace=$1
  local -r component=$2
  local -r run_id=$3
  local -r migration_id=$4
  local -r internal=$5
  local -r restore_cluster=$6 # optional, cluster to restore into (if different than current)
  MAX_RETRIES=20
  retry_count=0

  local stack

  stack=$(get_stack_for_namespace_component "$namespace" "$component" "$internal")
  instance="$(create_component_instance "$component" "$migration_id" "$namespace" "$internal")"

  cloudsql_backup_instance_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack")
  cloudsql_restore_instance_id=$cloudsql_backup_instance_id

  if [ -n "$restore_cluster" ]; then
    cloudsql_restore_instance_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack" "$restore_cluster")
    _info "Using restoring from $cloudsql_backup_instance_id into $cloudsql_restore_instance_id"
  fi

  backup_id=$(gcloud sql backups list --instance "$cloudsql_backup_instance_id" --filter="description=\"$run_id\"" --format=json | jq -r '.[].id')

  _warning "This operation will restore the CloudSQL DB instance $cloudsql_restore_instance_id from backup, overwriting its current data."
  _warning "Please consider backing up and/or cloning the DB instance before continuing."
  await_confirmation

  echo "Waiting for any operations on $cloudsql_restore_instance_id to finish"

  # Wait for any existing operations to finish (to avoid e.g. conflicting with automated periodic backups)
  gcloud sql operations list --instance="$cloudsql_restore_instance_id" --filter='NOT status:done' --format='value(name)' | xargs -r gcloud sql operations wait

  echo "All operations finished"

  _info "Restoring CloudSQL DB instance $cloudsql_backup_instance_id from backup $backup_id to $cloudsql_restore_instance_id"

  until [ $retry_count -gt $MAX_RETRIES ]; do
    # disabling exit on error to allow for retries
    set +e
    output=$(gcloud sql backups restore "$backup_id" --restore-instance="$cloudsql_restore_instance_id" --backup-instance="$cloudsql_backup_instance_id" --quiet 2>&1)
    restore_exit_code=$?
    set -e

    if [ $restore_exit_code -ne 0 ]; then
      if [[ $output == *"another operation was already in progress"* ]]; then
        _error_msg "Restore failed due to another operation in progress, retrying: $output"
      else
        _error_msg "$output"
      fi
      retry_count=$((retry_count+1))
      sleep 10
    else
      echo "Restore succeeded"
      return 0
    fi


    if [ $retry_count -gt $MAX_RETRIES ]; then
      _error "Restore of DB instance $cloudsql_restore_instance_id from backup $backup_id ($cloudsql_backup_instance_id) exceeded max retries"
      return 1
    fi
  done
}

function restore_component() {
  local -r namespace=$1
  local -r component=$2
  local -r migration_id=$3
  local -r run_id=$4
  local -r internal=$5
  local -r restore_cluster=$6 # cluster to restore into (if different from current)
  local -r hyperdisk_enabled=$7
  local -r deployment_names=$(component_to_deployments "$component" "$migration_id")
  local stack

  stack=$(get_stack_for_namespace_component "$namespace" "$component" "$internal")

  if [ "$component" == "cometbft" ]; then
    _info "Restoring cometbft"
    kubectl scale deployment -n "$namespace" "${deployment_names}" --replicas=0

    local cometbft_pvc_name
    local cometbft_snapshot_name
    if [ "$hyperdisk_enabled" = "true" ]; then
      cometbft_pvc_name="cometbft-migration-${migration_id}-hd-pvc"
      cometbft_snapshot_name="${cometbft_pvc_name}-$run_id"
    else
      cometbft_pvc_name="global-domain-$migration_id-cometbft-cometbft-data"
      cometbft_snapshot_name="${cometbft_pvc_name}-$run_id"
    fi

    restore_pvc_from_snapshot "$namespace" "$cometbft_snapshot_name" "$cometbft_pvc_name" "premium-rwo"
    kubectl scale deployment -n "$namespace" "${deployment_names}" --replicas=1
  else
    _info "Restoring $component"
    instance="$(create_component_instance "$component" "$migration_id" "$namespace" "$internal")"
    type=$(get_postgres_type "$namespace-$instance-pg" "$stack")
    case "$type" in
      "canton:network:postgres")
        restore_pvc_postgres "$namespace" "$instance" "$run_id" "$hyperdisk_enabled"
        ;;
      "canton:cloud:postgres")
        restore_cloudsql_postgres "$namespace" "$component" "$run_id" "$migration_id" "$internal" "$restore_cluster"
        ;;
      *)
        _error "Unknown postgres type: $type"
        ;;
    esac
  fi
}

function wait_cloudsql_restore() {
  local -r namespace=$1
  local -r component=$2
  local -r internal=$3

  local stack
  stack=$(get_stack_for_namespace_component "$namespace" "$component" "$internal")
  instance="$(create_component_instance "$component" "$migration_id" "$namespace" "$internal")"
  cloudsql_restore_instance_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack")

  local -i i=0
  _info "Waiting for restore of $component to finish..."

  # Sleeping for 30 here because it's possible we are being rate limited
  retry_sleep_time=30
  should_not_retry=0
  while [[ $should_not_retry = 0 ]]; do

    # Using conditional execution to prevent automatic exit
    if ! gcloud_output=$(gcloud sql operations list --instance="$cloudsql_restore_instance_id" --filter="(operationType=RESTORE_VOLUME AND status!=DONE)" --format=json 2>&1); then
      _error "Error fetching SQL operations: $gcloud_output" >&2
      sleep "$retry_sleep_time"
      continue 1
    fi

    if ! echo "$gcloud_output" | jq empty &>/dev/null; then
      _error "Error: Invalid JSON output from gcloud command"
      _error "Output was: $gcloud_output"
      sleep "$retry_sleep_time"
      continue 1
    fi

    num_running=$(echo "$gcloud_output" | jq length)

    if [ "$num_running" == 0 ]; then
      _info "Restore of instance $component done"
      should_not_retry=1
    else
      ((i++)) && ((i > 300)) && _error "Timed out waiting for backup restore of $component"
      sleep "$retry_sleep_time"
      _info "still waiting..."
    fi
  done
}

function wait_restore_component() {
  local -r namespace=$1
  local -r component=$2
  local -r internal=$3
  local stack
  stack=$(get_stack_for_namespace_component "$namespace" "$component" "$internal")

  if [ "$component" == "cometbft" ]; then
    _info "Nothing to do, cometbft restore is currently synchronous"
  else
    instance="$(create_component_instance "$component" "$migration_id" "$namespace" "$internal")"
    type=$(get_postgres_type "$namespace-$instance-pg" "$stack")
    case "$type" in
      "canton:network:postgres")
        _info "Nothing to do, self-hosted postgres restore is currently synchronous"
        ;;
      "canton:cloud:postgres")
        wait_cloudsql_restore "$namespace" "$component" "$internal"
        ;;
      *)
        _error "Unknown postgres type: $type"
        ;;
    esac
  fi
}

function usage() {
  echo "Usage: $0 [-r <restore_cluster>] <namespace> <run_id> <component>..."
}

function main() {
  if [ "$#" -lt 5 ]; then
    usage
    exit 1
  fi

  force=0
  POSITIONAL_ARGS=()
  restore_cluster=""
  while [[ $# -gt 0 ]]; do
      case $1 in
          --force)
              force=1
              shift
              ;;
          -r)
              restore_cluster=$2
              shift 2
              ;;
          -*)
              _error "Unknown option $1"
              ;;
          *)
              POSITIONAL_ARGS+=("$1")
              shift
              ;;
      esac
  done
  set -- "${POSITIONAL_ARGS[@]}"

  local -r namespace=$1
  local -r migration_id=$2
  local -r run_id=$3
  local -r internal=$4

  # Get resolved config and extract hyperdisk support flag
  local config
  config=$(get_resolved_config)
  local hyperdisk_enabled
  hyperdisk_enabled=$(echo "$config" | yq '.cluster.hyperdiskSupport.enabled // false')

  for component in "${@:5}"; do
    # verify all components exist and have a mapping
    component_to_deployments "$component" "$migration_id"
  done

  _info " ** Scaling down ** "
  for component in "${@:5}"; do
    down "$namespace" "$component" "$migration_id"
  done

  for component in "${@:5}"; do
    wait_down "$namespace" "$component" "$migration_id"
  done

  _info " ** Restoring ** "
  for component in "${@:5}"; do
    restore_component "$namespace" "$component" "$migration_id" "$run_id" "$internal" "$restore_cluster" "$hyperdisk_enabled"
  done

  _info " ** Waiting for all restore operations to finish ** "
  for component in "${@:5}"; do
    wait_restore_component "$namespace" "$component" "$internal"
  done


  _info " ** Scaling up ** "
  for component in "${@:5}"; do
    up "$namespace" "$component" "$migration_id"
  done


}

main "$@"
