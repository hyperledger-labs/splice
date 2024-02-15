#!/bin/bash
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
