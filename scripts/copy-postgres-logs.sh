#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

TMP_FILE="/tmp/postgresql.csv"
LOG_DIR="${SPLICE_ROOT}/log"

docker cp postgres-for-splice-node:var/lib/postgresql/data/log/postgresql.csv "${TMP_FILE}"

# Convert CSV to JSON format
python -c '
import csv, json, sys

csv.field_size_limit(sys.maxsize)

field_names = [str(n) for n in range(0, 18)]
csv_reader = csv.DictReader(sys.stdin, field_names)

for r in csv_reader:
  timestamp = (r["0"][:-3] + "Z").replace(" ", "T")

  print(json.dumps({"@timestamp": timestamp, "message": r["13"], "level": r["11"]}))
' < "${TMP_FILE}" > "${LOG_DIR}/postgres.clog"
