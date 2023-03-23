#!/usr/bin/env bash
set -eou pipefail

echo "Generating envoy.yaml config file..."
# shellcheck disable=SC2016
envsubst '$GRPC_ADDRESS,$GRPC_PORT,$GRPC_WEB_PORT' < /tmpl/envoy.yaml.tmpl > /etc/envoy.yaml

echo "Starting Envoy..."
# base-id allows multiple instances of envoy to co-exist in the same pod
/usr/local/bin/envoy -c /etc/envoy.yaml --base-id "$GRPC_PORT"
