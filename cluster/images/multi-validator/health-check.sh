#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

function check_endpoint() {
    local node_number="$1"
    local endpoint="$2"

    local base_port=$(( 5000 + ( node_number * 100 ) ))
    local validator_admin_port=$(( base_port + 3 ))

    local status
    status=$(wget --server-response --quiet "http://127.0.0.1:${validator_admin_port}/$endpoint" 2>&1 | awk 'NR==1{print $2}')

    if [[ "$status" != "200" ]];
    then
        echo "Status for $validator_admin_port is $status"
        exit 1
    fi
}

nodes=${NUM_NODES:-1}
endpoint="$1"
for i in $( seq 0 $(( nodes - 1 )) )
do
    check_endpoint "$i" "$endpoint"
done
