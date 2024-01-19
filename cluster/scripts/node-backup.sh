#!/usr/bin/env bash

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
source "${REPO_ROOT}/cluster/scripts/utils.source"

RUN_ID=$(date +%s)

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

  local -i i=0

  _info "Waiting for $description PVC backup to complete..."
  while true; do
    ready=$(kubectl get volumesnapshot -n "$namespace" -o json "$backupName" | jq .status.readyToUse)
    if [ "$ready" == "true" ]; then
      _info "Backup of $description PVC ready!"
      break
    else
      (( i++ )) && (( i > 300 )) && _error "Timed out waiting for backup of $description PVC"
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

  # Wait for any existing operations to finish (to avoid e.g. conflicting with automated periodic backups)
  gcloud sql operations list --instance="$db_id" --filter='NOT status:done' --format='value(name)' | xargs -r gcloud sql operations wait

  _info "Starting backup of $description db ($db_id)"
  gcloud sql backups create --instance "$db_id" --description "$RUN_ID" --async
}

function wait_for_cloudsql_backup() {
  local description=$1
  local instance=$2

  db_id=$(get_cloudsql_id "$instance")

  local -i i=0

  _info "Waiting for $description db backup to complete..."
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

  local full_instance="$namespace-$instance"

  type=$(get_postgres_type "$full_instance")

  if [ "$type" == "canton:network:postgres" ]; then
    backup_pvc_postgres "$description" "$namespace" "$instance"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    backup_cloudsql "$description" "$full_instance"
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
    if [ "$component" == "cometbft-0" ]; then
      backup_pvc "cometBFT" "$namespace" "global-domain-0-cometbft-cometbft-data"
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
    if [ "$component" == "cometbft-0" ]; then
      wait_for_pvc_backup "cometBFT" "$namespace" "global-domain-0-cometbft-cometbft-data"
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

  # TODO(#9361): support multiple domains / non-default-ID'd ones

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
    backup_component "$namespace" "sv-app-0" "$requested_component"
    backup_component "$namespace" "mediator-0" "$requested_component"
    backup_component "$namespace" "sequencer-0" "$requested_component"
    backup_component "$namespace" "cometbft-0" "$requested_component"

    wait_for_backup "$namespace" "validator" "$requested_component"
    wait_for_backup "$namespace" "scan" "$requested_component"
    wait_for_backup "$namespace" "sv-app-0" "$requested_component"

    # CN apps must be strictly before participant, so we sync on apps before starting the participant backup
    backup_component "$namespace" "participant-0" "$requested_component"

    wait_for_backup "$namespace" "participant-0" "$requested_component"
    wait_for_backup "$namespace" "mediator-0" "$requested_component"
    wait_for_backup "$namespace" "sequencer-0" "$requested_component"
    wait_for_backup "$namespace" "cometbft-0" "$requested_component"
  else
    usage
    exit 1
  fi

  _info "Completed all backups for namespace $namespace, RUN_ID = $RUN_ID"
}

main "$@"
