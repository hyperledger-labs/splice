#!/bin/bash

set -eou pipefail

config_file="${1-}"
if [ -z "$config_file" ]; then
  echo "Error: Path to config.toml was not specified"
  echo "USAGE: $0 <config-file>"
  exit 1
fi

rpc_servers="${STATE_SYNC_RPC_SERVERS:-""}"

IFS=',' read -ra _servers <<< "$rpc_servers"
rpc_server="${_servers[0]}"

# Identify trust_height and trust_hash
output=$( curl -s --fail -X GET "http://$rpc_server/commit" )
trust_height=$( jq -r '.result.signed_header.header.height' <<<"$output" )
trust_hash=$( jq -r '.result.signed_header.commit.block_id.hash' <<<"$output" )

# Update CometBFT config
echo "Setting rpc_servers to $rpc_servers"
echo "Setting trust_height to $trust_height"
echo "Setting trust_hash to $trust_hash"
sed -i "s/^rpc_servers = .*$/rpc_servers = \"${rpc_servers}\"/" "${config_file}"
sed -i "s/^trust_height = .*$/trust_height = ${trust_height-0}/" "${config_file}"
sed -i "s/^trust_hash = .*$/trust_hash = \"${trust_hash}\"/" "${config_file}"
