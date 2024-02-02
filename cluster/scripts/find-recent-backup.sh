#!/usr/bin/env bash

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
source "${REPO_ROOT}/cluster/scripts/utils.source"

# TODO(#9361): For now, we assume the latest backup was a full one (all components).

function usage() {
  echo "Usage: $0 <namespace>"
}

function main() {
  if [ "$#" -lt 1 ]; then
      usage
      exit 1
  fi

  local namespace=$1
  local component="validator"
  case "$namespace" in
      sv-1|sv-2|sv-3|sv-4)
          component="validator-0"
          ;;
  esac
  local full_instance="$namespace-$component-pg"

  type=$(get_postgres_type "$full_instance")

  if [ "$type" == "canton:network:postgres" ]; then
    # Since we assume that the latest backup was a full one, it suffices to find any volumesnapshot in this namespace
    backup_run_id=$(kubectl get volumesnapshot -n "$namespace" --sort-by=.metadata.creationTimestamp -o json | jq -r '.items[-1].metadata.name' | grep -o '[^-]*$')
    echo "$backup_run_id"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    # Since we assume that the latest backup was a full one, we just find the latest backup on the validator's db.
    cloudsql_id=$(get_cloudsql_id "$full_instance")
    # We always create backups with a description field while this field could be missing for automated backups done by Google Cloud.
    # So we filter those out while looking for the most recent backup.
    backup_run_id=$(gcloud sql backups list --instance "$cloudsql_id" --filter=type="ON_DEMAND" --format=json | jq -r '.[] | select(has("description")) | .description' | sort -n | tail -1)
    echo "$backup_run_id"
  elif [ -z "$type" ]; then
    _error "No postgres instance $full_instance found. Is the cluster deployed with split DB instances?"
  else
    _error "Unknown postgres type: $type"
  fi
}

main "$@"
