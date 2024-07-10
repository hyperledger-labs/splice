#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# We are not crashing on failures in this script, as the VPN may already be down



echo "killing VPN process..."
/usr/bin/sudo pkill charon-cmd | tee -a /var/log/vpn.log || true

echo "Deleting table 220"
/usr/bin/sudo ip route flush table 220

