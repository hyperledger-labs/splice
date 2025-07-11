#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Generate the private key
openssl genpkey -algorithm ed25519 -out cometbft-governance-keys.pem

# Extract and encode the keys
public_key_base64=$(openssl pkey -in cometbft-governance-keys.pem -pubout -outform DER | tail -c 32 | base64 | tr -d "\n")
private_key_base64=$(openssl pkey -in cometbft-governance-keys.pem -outform DER | tail -c 32 | base64 | tr -d "\n")

echo "{"
# Output the keys
echo "  \"public\": \"$public_key_base64\","
echo "  \"private\": \"$private_key_base64\""
echo "}"

# Clean up
rm cometbft-governance-keys.pem
