#!/usr/bin/env bash

# We are not crashing on failures in this script, as the VPN may already be down

echo "killing VPN process..."
/usr/bin/sudo pkill charon-cmd | tee -a /var/log/vpn.log || true

echo "Deleting table 220"
/usr/bin/sudo ip route flush table 220

