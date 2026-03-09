#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Schedules a logical synchronizer upgrade in a CI cluster.
# Based on vote-for-migration.sh

# curl uses --retry-all-errors because some 4xx returned by canton are transient errors that must be retried

# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

function usage() {
  echo "Usage: ./vote-for-lsu.sh <topology_freeze_time> <upgrade_time> <new_physical_synchronizer_serial> <new_physical_synchronizer_protocol_version>"
  echo ""
  echo "Arguments:"
  echo "  topology_freeze_time                  ISO 8601 UTC datetime at which topology transactions will be frozen (e.g. 2026-03-09T12:00:00Z)"
  echo "  upgrade_time                          ISO 8601 UTC datetime at which the upgrade to the new physical synchronizer will happen (e.g. 2026-03-09T12:05:00Z)"
  echo "  new_physical_synchronizer_serial      Serial of the new physical synchronizer (integer, usually old serial + 1)"
  echo "  new_physical_synchronizer_protocol_version  Protocol version of the new physical synchronizer (e.g. 34)"
}

if [ $# -lt 4 ]; then
  usage
  exit 1
fi

topology_freeze_time=$1
upgrade_time=$2
new_physical_synchronizer_serial=$3
new_physical_synchronizer_protocol_version=$4

echo "Creating vote request for logical synchronizer upgrade"
echo "  topology_freeze_time:                    $topology_freeze_time"
echo "  upgrade_time:                            $upgrade_time"
echo "  new_physical_synchronizer_serial:        $new_physical_synchronizer_serial"
echo "  new_physical_synchronizer_protocol_version: $new_physical_synchronizer_protocol_version"

sv1_token=$(cncluster get_token sv-1 sv)

current_dso_config=$(curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/dso")
echo "DSO: $current_dso_config"
current_config=$(echo "$current_dso_config" | jq -r '.dso_rules.contract.payload.config')
echo "Current config: $current_config"

requester=$(echo "$current_dso_config" | jq '.sv_party_id')
expiration_microseconds='60000000'
new_config=$(echo "$current_config" | jq \
  '.nextScheduledLogicalSynchronizerUpgrade = {
    "topologyFreezeTime": "'"$topology_freeze_time"'",
    "upgradeTime": "'"$upgrade_time"'",
    "newPhysicalSynchronizerSerial": '"$new_physical_synchronizer_serial"',
    "newPhysicalSynchronizerProtocolVersion": "'"$new_physical_synchronizer_protocol_version"'"
  }')

data='
{
  "requester": '$requester',
  "action": {
    "tag": "ARC_DsoRules",
    "value": {
      "dsoAction": {
        "tag": "SRARC_SetConfig",
        "value": {
          "newConfig": '"$new_config"'
        }
      }
    }
  },
  "url": "No URL",
  "description": "Triggered via vote-for-lsu script.",
  "expiration": {
    "microseconds": "'$expiration_microseconds'"
  }
}
'

echo "Sending vote request: $data"

curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
  -X POST "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/voterequest/create" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $sv1_token" \
  --data-raw "$data"

vote_requests=$(curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
  -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/voterequests" \
  -H "Authorization: Bearer $sv1_token")
echo "Found vote requests: $vote_requests"

vote_request_cid=$(echo "$vote_requests" | jq -r '.dso_rules_vote_requests[0].contract_id')

vote_data='{
  "vote_request_contract_id":"'"$vote_request_cid"'",
  "is_accepted":true,
  "reason_url":"No URL",
  "reason_description":"Accepted via vote-for-lsu script"
}'
echo "Casting votes on $vote_request_cid with: $vote_data"

other_svs=()

# standard (eng) SVs
DSO_SIZE=${DSO_SIZE:-3}
for ((i=2; i<=DSO_SIZE; i++)); do
  other_svs+=("sv-$i")
done

# extra SVs from all the config.yaml files
extra_svs=$(get_resolved_config | yq '.svs | keys | .[] | select(test("^(default|sv-[0-9]+)$") | not)')
for sv in $extra_svs; do
  other_svs+=("$sv")
done

for sv in "${other_svs[@]}"
do
  token=$(cncluster get_token "$sv" sv)
  echo "Casting vote on $sv"
  subdomain=$(get_resolved_config | yq ".svs.$sv.subdomain // \"$sv-eng\"")

  curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
    -X POST "https://sv.$subdomain.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/votes" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" \
    --data-raw "$vote_data"
done

# poll until the vote has gone through
# 300 to account for
# - 60s expiration time of the vote request
# - 60s buffer because we only set minutes not seconds
# - 30s polling interval for the trigger to kick in
# - general slowness
MAX_RETRIES=300
retry_count=0
until [ $retry_count -gt $MAX_RETRIES ]; do
  echo "Checking whether DSO info has been updated"
  retry_count=$((retry_count+1))

  # disabling exit on error to allow for retries
  set +e
  new_dso_config=$(curl -s -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/dso")
  echo "DSO info: $new_dso_config"
  actual_upgrade_time=$(echo "$new_dso_config" | jq -r '.dso_rules.contract.payload.config.nextScheduledLogicalSynchronizerUpgrade.upgradeTime')
  set -e
  echo "Upgrade time: $actual_upgrade_time"

  if [ "$actual_upgrade_time" == "$upgrade_time" ]; then
    echo "Logical synchronizer upgrade has been successfully scheduled"
    break
  fi

  if [ $retry_count -gt $MAX_RETRIES ]; then
    echo "DSO info was not updated after $MAX_RETRIES retries"
    exit 1
  fi

  sleep 1
done
