#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

text="$1"
# avoid sending notifications for scratch clusters
if grep -q "cluster scratchnet" <<<"$text"
then
  echo "skipping slack notification for scratch cluster"
  exit 0
fi

# The default channel is #team-canton-network-internal-ci
channel="${2:-C05DT77QF5M}"

echo "posting slack message"

curl -X POST --url "https://slack.com/api/chat.postMessage" \
  -H "Content-type: application/json" \
  -H "Authorization: Bearer $SLACK_ACCESS_TOKEN" \
  --data '{ "text": "'"$text"'", "channel": "'"$channel"'" }'
