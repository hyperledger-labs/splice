#!/usr/bin/env bash

set -eoux pipefail
# Virtual IP setup by charon-cmd, we assume this is only ip4 interface without a broadcast address.
echo "Dumping full ip config for debugging"
/usr/bin/sudo ip addr
echo "Querying for VPN IP"
VPN_IP=$(/usr/bin/sudo ip --json addr show dev ens5 | tee /dev/stderr | jq -r '.[0].addr_info | .[] | select(.family == "inet" and (has("broadcast") | not)) | .local')
[[ -z "$VPN_IP" ]] && { echo "Cannot find VPN_IP, please check VPN connectivity" ; exit 1; }
echo "Querying for network gateway"
GATEWAY=$(/usr/bin/sudo ip --json route list | tee /dev/stderr | jq -r '.[]|select(.gateway)|.gateway')
echo "Querying for egress IP"
EGRESS_IP=$(getent hosts "$GCP_CLUSTER_BASENAME".network.canton.global | tee /dev/stderr | awk '{ print $1 }')
/usr/bin/sudo ip route add "$EGRESS_IP" via "$GATEWAY" table 220 src "$VPN_IP"
KUBE_IP=$(sed -E -n 's|^.*server: https://(.*)$|\1|p' .kubecfg)
echo "Setting VPN IP"
/usr/bin/sudo ip route add "$KUBE_IP" via "$GATEWAY" table 220 src "$VPN_IP"
