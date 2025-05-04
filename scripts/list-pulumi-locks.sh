#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

pattern=${1:-""}
allLocks=$(curl -s -X GET -H "Authorization: Bearer $(gcloud auth print-access-token)" "https://storage.googleapis.com/storage/v1/b/cn-pulumi-stacks/o?prefix=.pulumi/locks")
output=$(echo "$allLocks" | jq -r ".items[] | select(.name | test(\"$pattern\")) | [.name,.timeCreated] | @tsv")
if [ -z "$output" ]; then
  exit 0
else
  echo -e "FILENAME\tTIME_CREATED\n$output" | column -t -s $'\t'
fi
