#!/bin/bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ "$APP_USER_PROFILE" = "on" ]; then
  echo "Checking 2${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpc-client-cli health "localhost:2${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
fi
if [ "$APP_PROVIDER_PROFILE" = "on" ]; then
  echo "Checking 3${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpc-client-cli health "localhost:3${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
fi
if [ "$SV_PROFILE" = "on" ]; then
  echo "Checking 4${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
  grpc-client-cli health "localhost:4${CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX}"
fi
