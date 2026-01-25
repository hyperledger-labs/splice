#!/bin/bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ "$APP_USER_PROFILE" = "on" ]; then
  curl -f "http://localhost:2${VALIDATOR_ADMIN_API_PORT_SUFFIX}/api/validator/readyz"
fi
if [ "$APP_PROVIDER_PROFILE" = "on" ]; then
  curl -f "http://localhost:3${VALIDATOR_ADMIN_API_PORT_SUFFIX}/api/validator/readyz"
fi
if [ "$SV_PROFILE" = "on" ]; then
  curl -f "http://localhost:4${VALIDATOR_ADMIN_API_PORT_SUFFIX}/api/validator/readyz"
  curl -f http://localhost:5012/api/scan/readyz
  curl -f http://localhost:5014/api/sv/readyz
fi
