#!/usr/bin/env bash

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
source "${REPO_ROOT}/cluster/scripts/utils.source"

declare -A component_to_deployment
component_to_deployment["sequencer-0"]="global-domain-0-sequencer"
component_to_deployment["mediator-0"]="global-domain-0-mediator"
component_to_deployment["participant"]="participant"
component_to_deployment["participant-0"]="participant-0"
component_to_deployment["cometbft-0"]="global-domain-0-cometbft"
component_to_deployment["validator"]="validator-app"
component_to_deployment["sv-app-0"]="sv-app-0"
component_to_deployment["scan"]="scan-app"

function create_pvc_from_snapshot() {
  local -r snapshot_name=$1
  local -r pvc_name=$2

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
  storageClassName: standard-rwo
  volumeMode: Filesystem
  dataSource:
    name: "$snapshot_name"
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
EOF
}

function restore_pvc_from_snapshot() {
  local -r snapshot_name=$1
  local -r pvc_name=$2

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
  create_pvc_from_snapshot "$snapshot_name" "$pvc_name"
}

function down() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  _info "Scaling down $component deployment"
  kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=0
}

function wait_down() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  _info "Waiting for all pods of $deployment_name to get deleted"
  kubectl wait pods -n "$namespace" -l "app=$deployment_name" --for delete --timeout=180s
}

function up() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  _info "Scaling up $component deployment"
  kubectl scale deployment -n "$namespace" "$deployment_name" --replicas=1
}

function restore_pvc_postgres() {
  local -r component=$1
  local -r deployment_name="${component_to_deployment[$component]}"

  local -r template_name="pg-data"
  local -r ss_name="$component-pg"
  local -r pg_pod_name="$ss_name-0"
  local -r pvc_name="$template_name-$pg_pod_name"
  local -r snapshot_name="$pvc_name-$run_id"

  _info "Scaling down postgres StatefulSet"
  kubectl scale statefulset -n "$namespace" "$ss_name" --replicas=0

  restore_pvc_from_snapshot "$snapshot_name" "$pvc_name"

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
  local -r component=$1

  cloudsql_id=$(get_cloudsql_id "$namespace-$component-pg")
  backup_id=$(gcloud sql backups list --instance "$cloudsql_id" --filter="description=\"$run_id\"" --format=json | jq -r '.[].id')

  _warning "This operation will restore the CloudSQL DB instance $cloudsql_id from backup, overwriting its current data."
  _warning "Please consider backing up and/or cloning the DB instance before continuing."
  await_confirmation

  _info "Restoring CloudSQL DB instance $cloudsql_id from backup $backup_id"
  gcloud sql backups restore "$backup_id" --restore-instance="$cloudsql_id" --backup-instance="$cloudsql_id" --quiet --async
}

function restore_component() {
  local -r component=$1

  if [ "$component" == "cometbft-0" ]; then
    _info "Restoring cometbft"
    kubectl scale deployment -n "$namespace" "${component_to_deployment[$component]}" --replicas=0
    restore_pvc_from_snapshot "global-domain-0-cometbft-cometbft-data-$run_id" "global-domain-0-cometbft-cometbft-data"
    kubectl scale deployment -n "$namespace" "${component_to_deployment[$component]}" --replicas=1
  else
    _info "Restoring $component"
    type=$(get_postgres_type "$namespace-$component-pg")
    case "$type" in
      "canton:network:postgres")
        restore_pvc_postgres "$component"
        ;;
      "canton:cloud:postgres")
        restore_cloudsql_postgres "$component"
        ;;
      *)
        _error "Unknown postgres type: $type"
        ;;
    esac
  fi
}

function wait_cloudsql_restore() {
  local -r component=$1

  cloudsql_id=$(get_cloudsql_id "$namespace-$component-pg")

  local -i i=0
  _info "Waiting for restore of $component to finish..."

  while true; do
    num_running=$(gcloud sql operations list --instance="$cloudsql_id" --filter="(operationType=RESTORE_VOLUME AND status!=DONE)" --format=json | jq length)
    if [ "$num_running" == 0 ]; then
      _info "Restore of instance $component done"
      break
    else
      (( i++ ))&& (( i > 300 )) &&_error "Timed out waiting for backup restore of $component"
      sleep 5
      _info "still waiting..."
    fi
  done
}

function wait_restore_component() {
  local -r component=$1

  if [ "$component" == "cometbft-0" ]; then
    _info "Nothing to do, cometbft restore is currently synchronous"
  else
    type=$(get_postgres_type "$namespace-$component-pg")
    case "$type" in
      "canton:network:postgres")
        _info "Nothing to do, self-hosted postgres restore is currently synchronous"
        ;;
      "canton:cloud:postgres")
        wait_cloudsql_restore "$component"
        ;;
      *)
        _error "Unknown postgres type: $type"
        ;;
    esac
  fi
}

function usage() {
  echo "Usage: $0 <namespace> <run_id> <component>..."
}

function main() {
  if [ "$#" -lt 3 ]; then
    usage
    exit 1
  fi

  force=0
  POSITIONAL_ARGS=()
  while [[ $# -gt 0 ]]; do
      case $1 in
          --force)
              force=1
              shift
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

  readonly namespace=$1
  readonly run_id=$2

  for component in "${@:3}"; do
    if [[ ! -v component_to_deployment["$component"] ]]; then
      _error "Unknown component: $component"
    fi
  done

  _info " ** Scaling down ** "
  for component in "${@:3}"; do
    down "$component"
  done

  for component in "${@:3}"; do
    wait_down "$component"
  done

  _info " ** Restoring ** "
  for component in "${@:3}"; do
    restore_component "$component"
  done

  _info " ** Waiting for all restore operations to finish ** "
  for component in "${@:3}"; do
    wait_restore_component "$component"
  done


  _info " ** Scaling up ** "
  for component in "${@:3}"; do
    up "$component"
  done


}

main "$@"
