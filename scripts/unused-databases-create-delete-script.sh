#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# To clean up dbs in a cluster run the following commands in the cluster directory:
# ./../../../scripts/unused-databases-create-delete-script.sh <migration_id>
# Copy the output of the script and then create a script using a debug container in the cluster:
# run `cncluster debug_shell`
# create a script with the output of the previous command
# add the PGUSER
# set the script as executable `chmod +x <script_name>`
# run the script `./<script_name>`

set -eou pipefail


# Check if migration_id is provided
if [ $# -lt 1 ]; then
    echo "Usage: $0 <migration_id> [--dry-run]"
    exit 1
fi

migration_id=$1

# Function to extract password from K8s secret
get_password() {
    local namespace=$1
    local secret_name=$2
    kubectl get secret -n "$namespace" "$secret_name" -o jsonpath='{.data.postgresPassword}' | base64 --decode
}

# Get all SQL instances with the specified label
instances=$(gcloud sql instances list --format="value(name)" --filter=labels.cluster:cilr )

echo "export PGUSER=cnadmin"
echo "export PGDATABASE=cantonnet"
# Process each instance
for instance in $instances
do

    if [[ $instance =~ ^sv-[0-9]+ ]]; then
        namespace=$(echo "$instance" | grep -oP 'sv-\d+')
        secret_suffix="secrets"
    elif [[ $instance =~ ^sv ]]; then
        namespace="sv"
        secret_suffix="secret"
    elif [[ $instance =~ ^.*-participant-.* ]]; then
        namespace=${instance%%-*}
        secret_suffix="secrets"
    else
        echo "# Could not extract namespace from instance $instance. Skipping."
        continue
    fi
    if [[ $instance == *sequencer* ]]; then
        if [[ $namespace == "sv" ]]; then
            db_name="sequencer_${migration_id}"
        else
            db_name="global_domain_${migration_id}_sequencer"
        fi
        secret_name="sequencer-pg-$secret_suffix"
    elif [[ $instance == *mediator* ]]; then
        if [[ $namespace == "sv" ]]; then
            db_name="mediator_${migration_id}"
        else
            db_name="global_domain_${migration_id}_mediator"
        fi
        secret_name="mediator-pg-$secret_suffix"
    elif [[ $instance == *participant* ]]; then
        db_name="participant_${migration_id}"
        secret_name="participant-pg-$secret_suffix"
    else
        continue
    fi

    # Get password from K8s secret
    password=$(get_password "$namespace" "$secret_name")

    if [[ -z "$password" ]]; then
        echo "# Could not retrieve password for instance $instance. Skipping."
        continue
    fi

    # Get the internal IP address for the instance
    internal_ip=$(gcloud sql instances describe "$instance" --format="value(ipAddresses[].ipAddress)")

    if [ -z "$internal_ip" ]; then
        echo "# No internal IP found for instance $instance. Skipping."
        continue
    fi

    # Construct and output the psql command
    echo "echo 'Dropping database $db_name on instance $instance'"
    echo "PGPASSWORD='$password' psql -h '$internal_ip' -c 'DROP DATABASE IF EXISTS $db_name;'"
done

echo "Done"
