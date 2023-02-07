#!/usr/bin/env bash
set -eou pipefail

echo "Generating envoy.yaml config file..."
cat /tmpl/envoy.yaml.tmpl | envsubst '$GRPC_ADDRESS,$GRPC_PORT,$GRPC_WEB_PORT' > /etc/envoy.yaml

echo "Starting Envoy..."
/usr/local/bin/envoy -c /etc/envoy.yaml
