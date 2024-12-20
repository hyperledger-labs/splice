#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Routes all traffic from a given docker bridge network through the VPN

set -eou pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <docker_network>"
  exit 1
fi

DOCKER_NETWORK=$1
DOCKER_BRIDGE=br-${DOCKER_NETWORK}

echo "Creating docker network ${DOCKER_NETWORK} with bridge ${DOCKER_BRIDGE}"
docker network create "${DOCKER_NETWORK}" -o com.docker.network.bridge.name="${DOCKER_BRIDGE}"

echo "Dumping iptables rules before changes, for debugging"
/usr/bin/sudo /usr/sbin/iptables --list-rules -t nat

VPN_IP=$(/usr/bin/sudo ip --json addr show | tee /dev/stderr | jq -r '.[] | select(.ifname | test("ens.*")) | .addr_info | .[] | select(.family == "inet" and (has("broadcast") | not)) | .local')
SUBNET=$(docker network inspect "${DOCKER_NETWORK}" | jq -r '.[0].IPAM.Config[0].Subnet')
/usr/bin/sudo iptables -D POSTROUTING -t nat -s "$SUBNET" ! -o "${DOCKER_BRIDGE}" -j MASQUERADE -t nat
/usr/bin/sudo iptables -I POSTROUTING -t nat -s "$SUBNET" ! -o "${DOCKER_BRIDGE}" -j SNAT --to-source "$VPN_IP" -t nat

echo "Dumping iptables rules after changes, for debugging"
/usr/bin/sudo /usr/sbin/iptables --list-rules -t nat
