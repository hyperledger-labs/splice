#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

if [ -z "${CI}" ]; then
    npm install --no-update-notifier
else
  # shellcheck disable=SC2015
  for _ in {1..5}; do npm ci && break || sleep 15; done
fi
