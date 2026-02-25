#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# check-cloudsql-conn.sh

echo "Checking connection to Splice services... Connecting to '$PERFORMANCE_TESTS_DB_HOST' with user '$PERFORMANCE_TESTS_DB_USER'..."
PGPASSWORD="$PERFORMANCE_TESTS_DB_PASSWORD" psql -h"$PERFORMANCE_TESTS_DB_HOST" -U"$PERFORMANCE_TESTS_DB_USER" -dcantonnet -c '\l' || (echo "Connection to DB failed" && exit 1)
