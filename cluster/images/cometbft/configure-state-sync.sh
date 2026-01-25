#!/bin/bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

config_file="${1}"
if [ -z "$config_file" ]; then
  echo "Error: Path to config.toml was not specified"
  echo "USAGE: $0 <config-file>"
  exit 1
fi

function _set_state_sync_disabled() {
  enable=false
  trust_height=0
  rpc_servers=""
  trust_hash=""
  trust_period="0h0m0s"
}

function _configure_state_sync() {
  # Log config changes before update (to aid debugging)
  echo
  echo "Setting state sync configuration to:"
  echo "  enable = $enable"
  echo "  rpc_servers = \"$rpc_servers\""
  echo "  trust_height = $trust_height"
  echo "  trust_hash = \"$trust_hash\""
  echo "  trust_period = \"$trust_period\""
  echo

  # Update CometBFT config
  sed -i "s|STATE_SYNC_ENABLE|${enable}|" "${config_file}"
  sed -i "s|STATE_SYNC_RPC_SERVERS|\"${rpc_servers}\"|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_HEIGHT|${trust_height}|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_HASH|\"${trust_hash}\"|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_PERIOD|\"${trust_period}\"|" "${config_file}"
}

function _json_rpc_call() {
  server_url="$1"
  request_data="$2"
  trap 'echo "Error: Failed to execute the curl command for URL: ${server_url}"' ERR
  curl -fsSL --connect-timeout 10 -H "Content-Type: application/json" -X POST -d "${request_data}" "${server_url}"
}

enable="${STATE_SYNC_ENABLE:-false}"
if [ "$enable" == "false" ]; then
  echo "State sync has been explicitly disabled."
  _set_state_sync_disabled
else
  rpc_servers="${STATE_SYNC_RPC_SERVERS}"
  if [ -z "$rpc_servers" ]; then
    echo "Env variable STATE_SYNC_RPC_SERVERS must be set to enable state sync"
    exit 1
  fi

  IFS=',' read -ra _servers <<< "$rpc_servers"
  base_url="${_servers[0]%/}"

  min_trust_height_age=${STATE_SYNC_MIN_TRUST_HEIGHT_AGE:-100}
  trust_period=${STATE_SYNC_TRUST_PERIOD:-"168h0m0s"}  # trust period defaults to 1 week
  latest_block_height=$( _json_rpc_call "${base_url}" '{"id": "0", "method": "status"}' | jq -r '.result.sync_info.latest_block_height' )
  echo "Latest block height: $latest_block_height"
  trust_height=$(( latest_block_height - min_trust_height_age ))
  # Disable state sync entirely if latest_block_height is less than min_trust_height_age
  if [ "$trust_height" -le 0 ]; then
    echo "Latest block height is not greater than the min trust height age $min_trust_height_age. State sync will be disabled."
    _set_state_sync_disabled
  elif [ "$trust_height" -eq 1 ]; then
    # The genesis block we add as part of our genesis file is dated as of 2023-02-27. With the default trust_period of 1 week,
    # most blocks would fall outside the trust_period window if we specify the trust_height for state sync as 1, resulting in state sync failures.
    # This is why we treat it as a special case and disable state sync in this scenario.
    echo "Trust height computed as 1. This would use the genesis block for state sync, which has a fixed date likely to be much older than the configured trust period. State sync will be disabled."
    _set_state_sync_disabled
  else
    enable=true
    trust_hash=$( _json_rpc_call "${base_url}" '{"id": "1", "method": "block", "params": {"height": "'"${trust_height}"'"}}' | jq -r '.result.block_id.hash' )
  fi
fi

_configure_state_sync
