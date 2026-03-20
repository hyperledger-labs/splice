#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Runs a SQL file on all cantonnet_v_NN databases across all postgres pods in the multi-validator namespace.
# This is analogous to `run-on-all-databases.sh`, but the multi-validator setup is distinct enough that
# adding support for this there just makes things more confusing.

if [ $# -lt 1 ]; then
    echo "Usage: $0 <sql_file>"
    echo "<sql_file> will not be run. Instead a series of commands will be outputted that can be run with cncluster debug_shell"
    exit 1
fi

sql_file=$1
namespace="multi-validator"

# Function to extract password from K8s secret
get_password() {
    local ns=$1
    local secret_name=$2
    kubectl get secret -n "$ns" "$secret_name" -o jsonpath='{.data.postgresPassword}' | base64 --decode
}

# Get all postgres pods matching the postgres-x-y naming pattern
pods=$(kubectl get pods -n "$namespace" -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\n' | grep -E '^postgres-[0-9]+-[0-9]+$' | sort)

if [ -z "$pods" ]; then
    echo "No postgres pods found in namespace $namespace matching pattern postgres-x-y."
    exit 1
fi

commands=()

# Process each postgres pod
for pod in $pods
do
    echo "# Discovering databases on pod $pod..."

    # List all cantonnet_v_NN databases on this pod
    databases=$(kubectl exec -n "$namespace" "$pod" -- psql -U cnadmin -d postgres -t -A -c \
        "SELECT datname FROM pg_database WHERE datname ~ '^cantonnet_v_[0-9]+$' ORDER BY datname;")

    if [ -z "$databases" ]; then
        echo "# No cantonnet_v_NN databases found on pod $pod. Skipping."
        continue
    fi

    # Get the pod IP
    pod_ip=$(kubectl get pod -n "$namespace" "$pod" -o jsonpath='{.status.podIP}')

    if [ -z "$pod_ip" ]; then
        echo "# No IP found for pod $pod. Skipping."
        continue
    fi

    # Extract the first number from the pod name (e.g., postgres-3-0 -> 3)
    pod_number=$(echo "$pod" | sed -E 's/^postgres-([0-9]+)-[0-9]+$/\1/')
    password=$(get_password "$namespace" "postgres-${pod_number}-secret")

    for db in $databases
    do
        # Derive schema name: cantonnet_v_NN -> cantonnet_validator_NN
        schema=$(echo "$db" | sed -E 's/^cantonnet_v_/cantonnet_validator_/')
        commands+=("PGPASSWORD='$password' PGOPTIONS=--search_path=${schema} psql -h '$pod_ip' -d ${db} -f $sql_file")
    done
done

echo "export PGUSER=cnadmin"
for ((i = 0; i < ${#commands[@]}; i++))
do
    echo "${commands[$i]}"
done

echo "Done. Run the commands above in a debug shell in the cluster you need."
echo "You'll need to kubectl cp or create the sql file with the same name in the debug shell."

