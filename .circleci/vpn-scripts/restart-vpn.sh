#!/bin/bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eoux pipefail

/tmp/vpn-scripts/stop-vpn.sh

echo "Starting VPN..." | tee -a /var/log/vpn.log
/tmp/vpn-scripts/connect-vpn.expect "$VPN_HOST_NAME" "$VPN_USER" "$VPN_PASSWORD" &

echo "Waiting for VPN to reconnect"
/tmp/vpn-scripts/wait-for-vpn-and-del-default-route.sh

echo "Authenticating to cluster again"
export KUBECONFIG=$PWD/.kubecfg
cncluster activate

echo "Setting VPN routes again"
/tmp/vpn-scripts/setup-vpn-routes.sh

if docker network inspect onvpn >/dev/null 2>&1; then
  echo "Setting up docker network again"
  docker network delete onvpn
  /tmp/vpn-scripts/create-docker-network-on-vpn.sh onvpn
fi
