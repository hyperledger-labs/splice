#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Generate the keypair
openssl ecparam -name prime256v1 -genkey -noout -out sv-keys.pem

# Encode the keys
public_key_base64=$(openssl ec -in sv-keys.pem -pubout -outform DER 2>/dev/null | base64 | tr -d "\n")
private_key_base64=$(openssl pkcs8 -topk8 -nocrypt -in sv-keys.pem -outform DER 2>/dev/null | base64 | tr -d "\n")

echo "{"
# Output the keys
echo "  \"publicKey\": \"$public_key_base64\","
echo "  \"privateKey\": \"$private_key_base64\""
echo "}"

# Clean up
rm sv-keys.pem
