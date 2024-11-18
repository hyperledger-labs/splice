#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -x

# shellcheck disable=SC2006
for subproject in `find . -path "*/target/test-reports" | sed -e 's/^\.\///' -e 's/\/target\/test-reports$//'`
do
  mkdir -p test-reports
  mkdir -p log
  # Keep going, if there is no test report.
  cp -v "$subproject"/target/test-reports/TEST-*.xml test-reports || true
done
