#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail


# Check if sql_file is provided
if [ $# -lt 2 ]; then
    echo "Usage: $0 <cluster> <sql_file>"
    exit 1
fi

cluster=$1
sql_file=$2

# Function to extract password from K8s secret
get_password() {
    local namespace=$1
    local secret_name=$2
    kubectl get secret -n "$namespace" "$secret_name" -o jsonpath='{.data.postgresPassword}' | base64 --decode
}

# Get all SQL instances with the specified label
instances=$(gcloud sql instances list --format="value(name)" --filter="labels.cluster:$cluster" | sort)

# Process each instance
for instance in $instances
do

    if [[ $instance =~ ^sv-([0-9]+)-cn\-apps-.* ]]; then
      # run on SV, scan & validator DBs
      n="${BASH_REMATCH[1]}"
      databases=(
        "scan_sv_$n"
        "sv_sv_$n"
        "validator_sv_$n"
      )
      password=$(get_password "sv-$n" "cn-apps-pg-secrets")
    elif [[ $instance =~ ^.*-validator\-pg-.* ]]; then
      # only run on validator DB
      databases=("validator1")
      password=$(get_password "validator1" "validator-pg-secrets")
    elif [[ $instance =~ ^.*splitwell\-sw-.* ]]; then
      # only run on splitwell DB
      databases=("app_splitwell")
      password=$(get_password "splitwell" "sw-pg-secrets")
    elif [[ $instance =~ ^.*-splitwell\-validator-.* ]]; then
      # only run on splitwell DB
      databases=("val_splitwell")
      password=$(get_password "splitwell" "validator-pg-secrets")
    else
      echo "Ignoring instance $instance"
      continue
    fi

    # Get the internal IP address for the instance
    internal_ip=$(gcloud sql instances describe "$instance" --format="value(ipAddresses[].ipAddress)")

    if [ -z "$internal_ip" ]; then
        echo "# No internal IP found for instance $instance. Skipping."
        continue
    fi

    # Construct and output the psql command
    for ((i = 0; i < ${#databases[@]}; i++))
    do
      commands+=("PGPASSWORD='$password' PGOPTIONS=--search_path=${databases[$i]} psql -h '$internal_ip' -d ${databases[$i]} -f $sql_file")
    done
done

echo "export PGUSER=cnadmin"
for ((i = 0; i < ${#commands[@]}; i++))
do
    echo "${commands[$i]}"
done


echo "Done"
