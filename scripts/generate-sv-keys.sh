#!/bin/bash

# Generate the keypair
openssl ecparam -name prime256v1 -genkey -noout -out sv-keys.pem

# Encode the keys
public_key_base64=$(openssl ec -in sv-keys.pem -pubout -outform DER 2>/dev/null | base64 -w 0)
private_key_base64=$(openssl pkcs8 -topk8 -nocrypt -in sv-keys.pem -outform DER 2>/dev/null | base64 -w 0)

# Output the keys
echo "public-key = \"$public_key_base64\""
echo "private-key = \"$private_key_base64\""

# Clean up
rm sv-keys.pem
