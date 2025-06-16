#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import requests
import json
from datetime import datetime, timezone, timedelta

def fetchTransactions(afterRecordTime, afterMigrationId, pageSize=1000):
  headers = {'Content-Type': 'application/json'}
  payload = {
    "page_size": pageSize,
    "after": {
      "after_record_time": afterRecordTime,
      "after_migration_id": afterMigrationId
    }
  }
  response = requests.post('https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v1/updates', headers=headers, json=payload)
  response.raise_for_status()
  transactions = response.json()
  transactions = transactions.get("transactions", [])
  if not transactions:
    print("Response was empty.")
    return []
  return transactions

start_time='2025-06-14T01:00:00.000000Z'
end_time='2025-06-14T03:00:00.000000Z'

while( datetime.strptime(start_time, '%Y-%m-%dT%H:%M:%S.%fZ') < datetime.strptime(end_time, '%Y-%m-%dT%H:%M:%S.%fZ')):
  print(f"Fetching transactions after {start_time}...")
  transactions = fetchTransactions(start_time, 2, 1000)
  for tx in transactions:
      for event in tx.get('events_by_id', {}).values():
        if event['event_type'] == "exercised_event" and event['choice'] == 'AmuletRules_BuyMemberTraffic':
            print(f"Update ID: {tx['update_id']}, Record Time: {tx['record_time']} has a traffic purchase event:")
            print(f"  Traffic Purchase: member ID: {event['choice_argument']['memberId']}, traffic amount: {event['choice_argument']['trafficAmount']}")
  start_time = transactions[-1]['record_time']


