#!/usr/bin/env bash

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

RUN_ID=$(date +%s)

function get_postgres_type() {
  local instance=$1

  cncluster pulumi canton-network stack export | grep -v "Running Pulumi Command" | jq -r ".deployment.resources[] | select(.urn | test(\".*canton:.*:postgres::${instance}\")) | .type"
}

function get_cloudsql_id() {
  local instance=$1

  cncluster pulumi canton-network stack export | grep -v "Running Pulumi Command" | jq -r ".deployment.resources[] | select(.urn | test(\".*DatabaseInstance::${instance}\")) | .id"
  # TODO(#8920): the component might actually be using a different database from that deployed in pulumi, e.g. if already recovered from backup once.
  # Or maybe we will actually import that restored DB into pulumi?
}

##### PVC Backup & Restore

function backup_pvc() {
  local description=$1
  local namespace=$2
  local pvc_name=$3

  local backupName="${pvc_name}-$RUN_ID"

  _info "Backing up PVC $description"

  kubectl apply -n "$namespace" -f - <<EOF
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshot
metadata:
  name: "$backupName"
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

  i=0

  _info "Waiting for $description PVC backup to complete..."
  while true; do
    ready=$(kubectl get volumesnapshot -n "$namespace" -o json "$backupName" | jq .status.readyToUse)
    if [ "$ready" == "true" ]; then
      _info "Backup of $description PVC ready!"
      break
    else
      (( i++ ))
      if [[ $i -eq 300 ]]; then
        _error "Timed out waiting for backup of $description PVC"
      fi
      sleep 5
      _info "still waiting..."
    fi
  done
}

function backup_pvc_postgres() {
  local description=$1
  local namespace=$2
  local instance=$3

  _info "** Backup up pvc-based postgres $description **"

  local pvc_name="pg-data-$instance-0"
  backup_pvc "$description" "$namespace" "$pvc_name"
}

#### CloudSQL Backup & Restore

function backup_cloudsql() {
  local description=$1
  local instance=$2

  _info "** Backing up $description **"

  _info "Looking for $description db"

  db_id=$(get_cloudsql_id "$instance")

  if [ -z "$db_id" ]; then
    _error "No CloudSQL instance $instance found"
  fi

  _info "Starting backup of $description db ($db_id)"
  gcloud sql backups create --instance "$db_id" --description "$RUN_ID" --async
}

function wait_for_cloudsql_backup() {
  local description=$1
  local instance=$2

  db_id=$(get_cloudsql_id "$instance")

  i=0

  _info "Waiting for $description db backup to complete..."
  while true; do
    backup=$(gcloud sql backups list --instance "$db_id" --filter="description=\"$RUN_ID\"" --format=json)
    status=$(echo "$backup" | jq -r '.[].status')
    id=$(echo "$backup" | jq -r '.[].id')

    if [ "$status" == "SUCCESSFUL" ]; then
      _info "Backup of $description ready! Backup ID: $id "
      break
    else
      (( i++ ))
      if [[ $i -eq 300 ]]; then
        _error "Timed out waiting for backup of $description db"
      fi
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

  local full_instance="$namespace-$instance"

  type=$(get_postgres_type "$full_instance")

  if [ "$type" == "canton:network:postgres" ]; then
    backup_pvc_postgres "$description" "$namespace" "$instance"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    backup_cloudsql "$description" "$full_instance"
  else
    _error "Unknown postgres type: $type"
  fi
}

function wait_for_postgres_backup() {
  local description=$1
  local namespace=$2
  local instance=$3

  local full_instance="$namespace-$instance"

  type=$(get_postgres_type "$full_instance")

  if [ "$type" == "canton:network:postgres" ]; then
    local pvc_name="pg-data-$instance-0"
    wait_for_pvc_backup "$description" "$namespace" "$pvc_name"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    wait_for_cloudsql_backup "$description" "$full_instance"
  else
    _error "Unknown postgres type: $type"
  fi

}

function backup_component() {
  local namespace=$1
  local component=$2
  local requested_component=$3

  if [ "$component" == "$requested_component" ] || [ -z "$requested_component" ]; then
    if [ "$component" == "cometbft" ]; then
      # TODO(#8920): replace the hard-coded "cometbft-data" with the actual pvc currently in use by the pod
      # (it might have been changed manually, e.g. if already recovered from backup)
      backup_pvc "cometBFT" "$namespace" "cometbft-data"
    else
      backup_postgres "$component" "$namespace" "$component-pg"
    fi
  else
    _info "Skipping backup of $component, not requested"
  fi
}

function wait_for_backup() {
  local namespace=$1
  local component=$2
  local requested_component=$3

  if [ "$component" == "$requested_component" ] || [ -z "$requested_component" ]; then
    if [ "$component" == "cometbft" ]; then
      # TODO(#8920): replace the hard-coded "cometbft-data" with the actual pvc currently in use by the pod
      # (it might have been changed manually, e.g. if already recovered from backup)
      wait_for_pvc_backup "cometBFT" "$namespace" "cometbft-data"
    else
      wait_for_postgres_backup "$component" "$namespace" "$component-pg"
    fi
  else
    _info "Skipping waiting for backup of $component, not requested"
  fi
}

function usage() {
  echo "Usage: $0 <sv|validator> <namespace> [<component_name>]"
}

function main() {
  if [ "$#" -lt 2 ]; then
      usage
      exit 1
  fi

  local namespace=$2
  local requested_component="${3:-}"

  if [ "$1" == "validator" ]; then
    _info "Backing up validator $namespace"
    backup_component "$namespace" "validator" "$requested_component"
    wait_for_backup "$namespace" "validator" "$requested_component"
    # CN apps must be strictly before participant, so we sync on apps before starting the participant backup
    backup_component "$namespace" "participant" "$requested_component"
    wait_for_backup "$namespace" "participant" "$requested_component"
  elif [ "$1" == "sv" ]; then
    _info "Backing up SV node $namespace"

    backup_component "$namespace" "validator" "$requested_component"
    backup_component "$namespace" "scan" "$requested_component"
    backup_component "$namespace" "sv" "$requested_component"
    backup_component "$namespace" "mediator" "$requested_component"
    backup_component "$namespace" "sequencer" "$requested_component"
    backup_component "$namespace" "cometbft" "$requested_component"

    wait_for_backup "$namespace" "validator" "$requested_component"
    wait_for_backup "$namespace" "scan" "$requested_component"
    wait_for_backup "$namespace" "sv" "$requested_component"

    # CN apps must be strictly before participant, so we sync on apps before starting the participant backup
    backup_component "$namespace" "participant" "$requested_component"

    wait_for_backup "$namespace" "participant" "$requested_component"
    wait_for_backup "$namespace" "mediator" "$requested_component"
    wait_for_backup "$namespace" "sequencer" "$requested_component"
    wait_for_backup "$namespace" "cometbft" "$requested_component"
  else
    usage
    exit 1
  fi

  _info "Completed all backups, RUN_ID = $RUN_ID"
}

main "$@"
