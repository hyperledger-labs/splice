#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

POSTGRES_MODE=docker # drop & create are the same for all modes

echo "Dropping DB for CN apps"
"${SPLICE_ROOT}"/scripts/postgres.sh "$POSTGRES_MODE" dropdb splice_apps

echo "Recreating DB for CN apps"
"${SPLICE_ROOT}"/scripts/postgres.sh "$POSTGRES_MODE" createdb splice_apps
