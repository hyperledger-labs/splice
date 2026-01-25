#!/bin/bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ "$APP_USER_PROFILE" = "on" ]; then
  echo "Checking 2${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpcurl -plaintext "localhost:2${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}" grpc.health.v1.Health/Check
fi
if [ "$APP_PROVIDER_PROFILE" = "on" ]; then
  echo "Checking 3${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpcurl -plaintext "localhost:3${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}" grpc.health.v1.Health/Check
fi
if [ "$SV_PROFILE" = "on" ]; then
  echo "Checking 4${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpcurl -plaintext "localhost:4${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}" grpc.health.v1.Health/Check
fi
