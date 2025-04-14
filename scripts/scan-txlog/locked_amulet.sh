#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

migration_id=$(curl -sSfL 'https://docs.global.canton.network.sync.global/info' | jq .sv.migration_id)
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
snapshot_time=$(curl -sSfL --location "https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v0/state/acs/snapshot-timestamp?before=${now}&migration_id=${migration_id}" | jq -r .record_time)

echo "Last snapshot for migration ${migration_id} is ${snapshot_time}"

page=$(curl -sSfL -X POST  --header "Content-Type: application/json" --location 'https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v0/state/acs' --data '{"migration_id": '"$migration_id"', "record_time": "'"$snapshot_time"'", "page_size": 1000}')
total=0

while true;
do
  echo "Next page: $page"
  echo "$page" | jq '.created_events | map(select(.template_id | test("LockedAmulet")))'
  next_page_token=$(echo "$page" | jq -r '.next_page_token')
  amounts=$(echo "$page" | jq '[.created_events[] | select(.template_id | test("LockedAmulet")) | .create_arguments.amulet.amount.initialAmount]')
  echo "All amounts in page: $amounts"
  sum=$(echo "$amounts" | jq 'map(tonumber) | add')
  echo "Sum of amounts in page: $sum"

  total=$(echo "$total + $sum" | bc)

  echo "Total so far: $total"

  if [ "$next_page_token" == "null" ]; then
    echo "Done!"
    echo ""
    echo "Total amount of LockedAmulet as of $snapshot_time: $total"
    break
  fi

  page=$(curl -sSfL -X POST  --header "Content-Type: application/json" --location 'https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v0/state/acs' --data '{"migration_id": '"$migration_id"', "record_time": "'"$snapshot_time"'", "page_size": 1000, "after": "'"$next_page_token"'" }')

done
