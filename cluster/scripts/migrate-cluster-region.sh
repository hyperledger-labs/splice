#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# This script is only an example / W.I.P., do not use unless testing!

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${REPO_ROOT}/cluster/scripts/utils.source"

SRC_CLUSTER="scratchnetb"
DEST_CLUSTER="scratchnete"

SRC_PATH="$REPO_ROOT/cluster/deployment/$SRC_CLUSTER"
DEST_PATH="$REPO_ROOT/cluster/deployment/$DEST_CLUSTER"

if [ ! -d "$SRC_PATH" ]; then
    _error "Source cluster $SRC_CLUSTER does not exist"
fi

if [ ! -d "$DEST_PATH" ]; then
    _error "Destination cluster $DEST_CLUSTER does not exist"
fi

cd "$SRC_PATH"

function run_prompt() {
    local msg="$1"
    shift
    local fn="$*"

    echo -e "$msg"
    read -r -p '[y/n]: '
    [ "${REPLY}" == 'y' ] || return 0 && {
        _header "Running $fn"
        $fn
    }
}

function scale() {
    local ns="$1"
    local replicas="$2"
    shift 2
    kubectl scale deployment --replicas="$replicas" -n "$ns" "$@"
}

# take a backup
run_prompt \
    "Backup validator1?" \
    "$REPO_ROOT/cluster/scripts/node-backup.sh" validator validator1 0 true

run_prompt \
    "Backup sv-1?" \
    "$REPO_ROOT/cluster/scripts/node-backup.sh" sv sv-1 0 true

# do a restore
function restore() {
    local namespace="$1"
    local migration_id="$2"
    local internal="true" # TODO(#16275): test this with external stacks as well

    shift 2

    backup_run_id=$("$REPO_ROOT"/cluster/scripts/find-recent-backup.sh "$namespace" "$migration_id" "$internal")
    if [[ -z "$backup_run_id" || "$backup_run_id" == "null" ]]; then
        _error "No recent backup found for $namespace"
    fi
    _info "Found latest backup run ID: $backup_run_id"

    SPLICE_SV="$namespace" SPLICE_MIGRATION_ID="$migration_id" "$REPO_ROOT/cluster/scripts/node-restore.sh" -r "$DEST_CLUSTER" "$namespace" "$migration_id" "$backup_run_id" "$internal" "$@"
}
run_prompt \
    "Restore validator1?" \
    restore validator1 0 participant validator

run_prompt \
    "Restore sv-1?" \
    restore sv-1 0 sequencer participant mediator cn-apps

# get the secrets
function reset_postgres_password() {
    local namespace="$1"
    local component="$2"
    local username="$3"
    local migration_id=0
    local internal=true
    local stack

    stack=$(get_stack_for_namespace_component "$namespace" "$component" "$internal")
    instance="$(create_component_instance "$component" "$migration_id" "$namespace" "$internal")"
    cloudsql_instance_id=$(get_cloudsql_id "$namespace-$instance-pg" "$stack" "$DEST_CLUSTER")

    local secret; secret=$(cd "$DEST_PATH" && direnv exec . kubectl get secret -n "$namespace" "$component-pg-secrets" -o jsonpath="{.data.postgresPassword}" | base64 -d)

    _warning "Setting password for $username in $cloudsql_instance_id"
    gcloud sql users set-password "$username" --instance="$cloudsql_instance_id" --password="$secret"
}
run_prompt "Reset validator1 participant-pg password?" reset_postgres_password validator1 participant cnadmin
run_prompt "Reset validator1 validator-pg password?" reset_postgres_password validator1 validator cnadmin

run_prompt "Reset sv-1 mediator-pg password?" reset_postgres_password sv-1 mediator cnadmin
run_prompt "Reset sv-1 sequencer-pg password?" reset_postgres_password sv-1 sequencer cnadmin
run_prompt "Reset sv-1 participant-pg password?" reset_postgres_password sv-1 participant cnadmin
run_prompt "Reset sv-1 cn-apps-pg password?" reset_postgres_password sv-1 cn-apps cnadmin
