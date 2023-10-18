#!/bin/bash

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

  # Update CometBFT config
  cp "${config_file}" "${config_file}.orig"  # copy config before making changes for later comparison
  sed -i "s|STATE_SYNC_ENABLE|${enable}|" "${config_file}"
  sed -i "s|STATE_SYNC_RPC_SERVERS|\"${rpc_servers}\"|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_HEIGHT|${trust_height}|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_HASH|\"${trust_hash}\"|" "${config_file}"
  sed -i "s|STATE_SYNC_TRUST_PERIOD|\"${trust_period}\"|" "${config_file}"

  # Log config diff after update (to aid debugging)
  echo
  echo "Configuration changes made:"
  diff -u "${config_file}.orig" "${config_file}" || :
  rm "${config_file}.orig"
}

enable="${STATE_SYNC_ENABLE:-false}"
if [ "$enable" == "false" ]; then
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
  latest_block_height=$( curl -sL --fail -X GET "${base_url}/status" | jq -r '.result.sync_info.latest_block_height' )
  echo "Latest block height: $latest_block_height"
  # Disable state sync entirely if latest_block_height is less than min_trust_height_age
  if [ "$latest_block_height" -le "$min_trust_height_age" ]; then
    _set_state_sync_disabled
  else
    enable=true
    trust_height=$(( latest_block_height - min_trust_height_age ))
    trust_hash=$( curl -sL --fail -X GET "${base_url}/block?height=${trust_height}" | jq -r '.result.block_id.hash' )
  fi
fi

_configure_state_sync
