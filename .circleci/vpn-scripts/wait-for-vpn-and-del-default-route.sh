#!/usr/bin/env bash

# We do NOT `set -e` in this script to just continue retrying on failures
set -u

count=0
ret=42
while [ ${count} -lt 180 ]; do
  if [ "$(curl checkip.amazonaws.com)" == "$VPN_HOST_IP" ]; then
    echo "Check IP passed"
    ret=0
    break
  else
    echo "Current IP is not desired VPN server IP. Retry count $count"
    (( count++ ))
    sleep 1
  fi
done

# We want to activate the VPN only for our k8s admin API
# and for the k8s egress. Therefore, we drop the default route.
# We do not prevent this in the charon-cmd configuration because
# our check above relies on that route.
/usr/bin/sudo ip route del default table 220

exit $ret
