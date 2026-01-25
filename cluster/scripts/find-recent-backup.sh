#!/usr/bin/env bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

function usage() {
  _info "Usage: $0 <namespace> <migration_id>"
}

function is_full_backup_kube() {
  local component_backup_names=$1
  local expected_components=$2

  # Check if all expected components can be found in the component_backup_names
  for component in $expected_components; do
    count=$(echo "$component_backup_names" | grep -c "$component")
    if [ "$count" -ne 1 ]; then
      return 1
    fi
  done

  return 0
}

function get_component_backup_names_kube() {
  local migration_id=$1
  local run_id=$2
  component_backup_names=$(kubectl get volumesnapshot -n "$namespace" --sort-by=.metadata.creationTimestamp -o json | jq "[.items[] | select(.metadata.annotations[\"migrationId\"] == \"$migration_id\") | select(.metadata.name | endswith(\"$run_id\"))]" | jq -r '.[].metadata.name // empty' )
  echo "$component_backup_names"
}

function latest_full_backup_run_id_kube() {
  local namespace=$1
  local migration_id=$2
  local is_sv=$3
  local expected_components=$4
  if [ "$is_sv" == "true" ]; then
      expected_components="$expected_components cometbft"
  fi

  local all_run_ids
  # get all run id postgres
  all_run_ids=$(kubectl get volumesnapshot -n "$namespace" --sort-by=.metadata.creationTimestamp -o json | jq "[.items[] | select(.metadata.annotations[\"migrationId\"] == \"$migration_id\")]" | jq -r '.[].metadata.name // empty' | grep -o '[^-]*$' | sort -rn | uniq )

  while read -r run_id; do
    component_backup_names=$(get_component_backup_names_kube "$migration_id" "$run_id")
    if is_full_backup_kube "$component_backup_names" "$expected_components"; then
      echo "$run_id"
      return 0
    fi
  done <<< "$all_run_ids"
  return 1
}

function latest_full_backup_run_id_gcloud() {
  local namespace=$1
  local migration_id=$2
  local is_sv=$3
  local expected_components=$4
  local num_components
  num_components=$(echo "$expected_components" | wc -w)
  local stack

  declare -A run_ids_dict

  for component in $expected_components; do
    stack=$(get_stack_for_namespace_component "$namespace" "$component")
    instance="$(create_component_instance "$component" "$migration_id" "$namespace")"
    local full_component_instance="$namespace-$instance-pg"

    local cloudsql_id
    cloudsql_id=$(get_cloudsql_id "$full_component_instance" "$stack")
    # We always create backups with a description field while this field could be missing for automated backups done by Google Cloud.
    # So we filter those out while looking for the most recent backup.
    mapfile -t run_ids < <(gcloud sql backups list --instance "$cloudsql_id" --filter=type="ON_DEMAND" --format=json | jq -r '.[] | select(has("description")) | .description')
    run_ids_dict[$component]="${run_ids[*]}"
  done

  if [ "$is_sv" == "true" ]; then
    mapfile -t cometbft_run_ids < <(kubectl get volumesnapshot -n "$namespace" --sort-by=.metadata.creationTimestamp -o json | jq "[.items[] | select(.metadata.annotations[\"migrationId\"] == \"$migration_id\") | select(.metadata.name | contains(\"cometbft\"))]" | jq -r '.[].metadata.name // empty' | grep -o '[^-]*$' | sort -rn | uniq )
    run_ids_dict["cometbft"]="${cometbft_run_ids[*]}"
    ((num_components++))
  fi

  declare -A count_dict
  for key in "${!run_ids_dict[@]}"; do
    for value in ${run_ids_dict[$key]}; do
      if [ -z "${count_dict[$value]+_}" ]; then
        count_dict[$value]=0
      fi
      ((count_dict[$value]++))
    done
  done

  largest_run_id=$(for key in "${!count_dict[@]}"; do
    if [ "${count_dict[$key]}" -eq "$num_components" ]; then
      echo "$key"
    fi
  done | sort -n | tail -n 1)

  echo "$largest_run_id"
}

function main() {
  if [ "$#" -lt 2 ]; then
      usage
      exit 1
  fi

  local namespace=$1
  local migration_id=$2

  case "$namespace" in
      sv-1|sv-2|sv-3|sv-4|sv-da-1)
          is_sv=true
          full_instance="$namespace-cn-apps-pg"
          expected_components="cn-apps sequencer participant mediator"
          stack=$(get_stack_for_namespace_component "$namespace" "cn-apps")
          ;;
      *)
          is_sv=false
          full_instance="$namespace-validator-pg"
          expected_components="validator participant"
          stack=$(get_stack_for_namespace_component "$namespace" "participant")
          ;;
  esac

  type=$(get_postgres_type "$full_instance" "$stack")
  # We only check the postgres type of one component and assume other components have the same type.
  if [ "$type" == "canton:network:postgres" ]; then
    backup_run_id=$(latest_full_backup_run_id_kube "$namespace" "$migration_id" "$is_sv" "$expected_components")
    echo "$backup_run_id"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    backup_run_id=$(latest_full_backup_run_id_gcloud "$namespace" "$migration_id" "$is_sv" "$expected_components")
    echo "$backup_run_id"
  elif [ -z "$type" ]; then
    _error "No postgres instance $full_instance found in stack ${stack}. Is the cluster deployed with split DB instances?"
  else
    _error "Unknown postgres type: $type"
  fi
}

main "$@"
