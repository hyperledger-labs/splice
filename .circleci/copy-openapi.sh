#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

mkdir -p /tmp/artifacts
mkdir -p /tmp/openapi
cp "apps/common/src/main/openapi/README.md" /tmp/openapi;
find . -ipath '*/openapi/*.yaml' -exec cp '{}' /tmp/openapi \;
cd /tmp/openapi || exit
tar -czvf openapi.tar.gz -- *
mv openapi.tar.gz ../artifacts
