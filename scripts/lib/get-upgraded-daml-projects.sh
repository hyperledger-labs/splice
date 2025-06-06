#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$SPLICE_ROOT"

# all directories below daml/ ending in '-upgrade' are projects to be diffed
find daml/ -type d -path 'daml/*-upgrade' | \
  sed 's/-upgrade$//; s/daml\///' | \
  sort
