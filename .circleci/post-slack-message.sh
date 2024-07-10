#!/bin/bash

set -euo pipefail

text="$1"
# The default channel is #team-canton-network-internal-alerts
channel="${2:-C064MTNQT88}"

echo "posting slack message"

curl -X POST --url "https://slack.com/api/chat.postMessage" \
  -H "Content-type: application/json" \
  -H "Authorization: Bearer $SLACK_ACCESS_TOKEN" \
  --data '{ "text": "'"$text"'", "channel": "'"$channel"'" }'
