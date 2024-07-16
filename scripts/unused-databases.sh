#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

databases=""

for stack in "$@"
do
    new_databases=$(cncluster pulumi "$stack" stack export | sed -n 's|.*instances/\(.*\)/databases.*|\1|p' | sort | uniq)
    databases="${databases}${new_databases}"
done
used_databases="$(echo "$databases" | sort | uniq)"
unused_dbs=()
all_databases=$(gcloud sql instances list --filter labels.cluster:cilr --format=json | jq -r '.[].name')
for database in $all_databases
do
    if [[ "$used_databases" != *$database* ]]
    then
       unused_dbs+=("$database")
    fi
done
printf '%s\n' "${unused_dbs[@]}" | sort
