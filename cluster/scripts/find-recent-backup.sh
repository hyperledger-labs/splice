#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

function usage() {
  _info "Usage: $0 <namespace> <migration_id> [<min_backup_age_hours>]"
  _info "  min_backup_age_hours: minimum age in hours — finds the most recent full backup that is at least this many hours old."
}

function is_full_backup_kube() {
  local component_backup_names=$1
  local expected_patterns=$2

  # Check if all expected components can be found in the component_backup_names
  for pattern in $expected_patterns; do
    count=$(echo "$component_backup_names" | grep -c -F -- "$pattern")
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
  local before_timestamp=$5
  local include_cometbft=$6

  local expected_patterns=""
  for component in $expected_components; do
    local instance
    instance="$(create_component_instance "$component" "$migration_id" "$namespace")"
    expected_patterns="${expected_patterns:+$expected_patterns }-${instance}-pg-"
  done

  if [ "$is_sv" == "true" ] && [ "$include_cometbft" == "true" ]; then
      expected_patterns="$expected_patterns cometbft"
  fi

  local all_run_ids
  # get all run id postgres
  all_run_ids=$(kubectl get volumesnapshot -n "$namespace" --sort-by=.metadata.creationTimestamp -o json | jq "[.items[] | select(.metadata.annotations[\"migrationId\"] == \"$migration_id\")]" | jq -r '.[].metadata.name // empty' | grep -o '[^-]*$' | awk -v ts="$before_timestamp" '$1 <= ts' | sort -rn | uniq )

  while read -r run_id; do
    component_backup_names=$(get_component_backup_names_kube "$migration_id" "$run_id")
    if is_full_backup_kube "$component_backup_names" "$expected_patterns"; then
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
  local before_timestamp=$5
  local stack

  declare -A backup_id_dict
  local sequencer_end_time=""

  # participant backup must be newer than cn-apps backup
  stack=$(get_stack_for_namespace_component "$namespace" "participant")
  instance="$(create_component_instance "participant" "$migration_id" "$namespace")"
  local participant_cloudsql_id
  participant_cloudsql_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack")
  local participant_entry
  participant_entry=$(gcloud sql backups list --instance "$participant_cloudsql_id" --format=json | jq -r --argjson ts "$before_timestamp" '[.[] | select(.endTime <= ($ts | todate))] | first | "\(.id) \(.endTime)"')
  local participant_backup_id
  participant_backup_id=$(echo "$participant_entry" | awk '{print $1}')
  local participant_end_time
  participant_end_time=$(echo "$participant_entry" | awk '{print $2}')
  if [ -z "$participant_backup_id" ] || [ "$participant_backup_id" == "null" ]; then
    _error "No backup found for participant (instance $participant_cloudsql_id) before timestamp $before_timestamp"
  fi
  backup_id_dict["participant"]="$participant_backup_id"

  for component in $expected_components; do
    [ "$component" == "participant" ] && continue
    [ "$component" == "cantonBft" ] && continue

    stack=$(get_stack_for_namespace_component "$namespace" "$component")
    instance="$(create_component_instance "$component" "$migration_id" "$namespace")"
    local full_component_instance="$namespace-$instance-pg"

    local cloudsql_id
    cloudsql_id=$(get_cloudsql_id "$full_component_instance" "$stack")

    local entry
    if [ "$component" == "cn-apps" ]; then
      # cn-apps backup must be older than participant backup
      entry=$(gcloud sql backups list --instance "$cloudsql_id" --format=json | jq -r --arg pt "$participant_end_time" '[.[] | select(.endTime <= $pt)] | first | "\(.id) \(.endTime)"')
    else
      entry=$(gcloud sql backups list --instance "$cloudsql_id" --format=json | jq -r --argjson ts "$before_timestamp" '[.[] | select(.endTime <= ($ts | todate))] | first | "\(.id) \(.endTime)"')
    fi
    local backup_id
    backup_id=$(echo "$entry" | awk '{print $1}')
    local backup_end_time
    backup_end_time=$(echo "$entry" | awk '{print $2}')

    if [ -z "$backup_id" ] || [ "$backup_id" == "null" ]; then
      _error "No backup found for component $component (instance $cloudsql_id) before timestamp $before_timestamp"
    fi

    backup_id_dict[$component]="$backup_id"

    if [ "$component" == "sequencer" ]; then
      sequencer_end_time="$backup_end_time"
    fi
  done

  # cantonBft backup must be older than the sequencer backup
  if [[ " $expected_components " == *" cantonBft "* ]]; then
    stack=$(get_stack_for_namespace_component "$namespace" "cantonBft")
    instance="$(create_component_instance "cantonBft" "$migration_id" "$namespace")"
    local bft_cloudsql_id
    bft_cloudsql_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack")
    local bft_backup_id
    bft_backup_id=$(gcloud sql backups list --instance "$bft_cloudsql_id" --format=json | jq -r --arg st "$sequencer_end_time" '[.[] | select(.endTime <= $st)] | first | .id')
    if [ -z "$bft_backup_id" ] || [ "$bft_backup_id" == "null" ]; then
      _error "No backup found for component cantonBft (instance $bft_cloudsql_id) before sequencer backup time $sequencer_end_time"
    fi
    backup_id_dict["cantonBft"]="$bft_backup_id"
  fi

  local result=""
  for component in "${!backup_id_dict[@]}"; do
    result="${result:+$result,}$component:${backup_id_dict[$component]}"
  done
  echo "$result"
}

function main() {
  if [ "$#" -lt 2 ]; then
      usage
      exit 1
  fi

  local namespace=$1
  local migration_id=$2

  local before_timestamp
  if [ "$#" -ge 3 ] && [ -n "$3" ]; then
     local min_backup_age_hours
     min_backup_age_hours=$3
     before_timestamp=$(( $(date +%s) - min_backup_age_hours * 3600 ))
    _info "Using backup from ${min_backup_age_hours} hours ago → before_timestamp=${before_timestamp}" >&2
  else
    before_timestamp=$(date +%s)
  fi

  local config
  config=$(get_resolved_config)
  local bft_sequencer_enabled
  bft_sequencer_enabled=$(echo "$config" | yq "
    ([.synchronizerMigration.active, .synchronizerMigration.upgrade, .synchronizerMigration.legacy]
      + (.synchronizerMigration.archived // [])
      + (.synchronizerMigration.additionalLegacy // []))
    | map(select(.id == $migration_id))
    | .[0].sequencer.enableBftSequencer // false")
  local include_cometbft="true"
  if [ "$bft_sequencer_enabled" == "true" ]; then
    include_cometbft="false"
  fi

  case "$namespace" in
      sv|sv-[0-9]|sv-[0-9][0-9]|sv-da-*)
          is_sv=true
          full_instance="$namespace-cn-apps-pg"
          expected_components="cn-apps sequencer participant mediator cantonBft"
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
    backup_run_id=$(latest_full_backup_run_id_kube "$namespace" "$migration_id" "$is_sv" "$expected_components" "$before_timestamp" "$include_cometbft")
    echo "$backup_run_id"
  elif [ "$type" == "canton:cloud:postgres" ]; then
    backup_map_id=$(latest_full_backup_run_id_gcloud "$namespace" "$migration_id" "$is_sv" "$expected_components" "$before_timestamp")
    echo "$backup_map_id"
  elif [ -z "$type" ]; then
    _error "No postgres instance $full_instance found in stack ${stack}. Is the cluster deployed with split DB instances?"
  else
    _error "Unknown postgres type: $type"
  fi
}

main "$@"
