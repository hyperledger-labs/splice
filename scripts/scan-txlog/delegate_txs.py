#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Computes the rate of transactions with delegate exercised choices as root event over the total of transactions
"""

import requests
import json
from datetime import datetime, timezone, timedelta

def getExercisedRootEvents(tx):
  ids = tx.get("root_event_ids")
  events = tx.get("events_by_id")
  return [e for e in [tx["events_by_id"][x] for x in filter(lambda x: x in events, ids)] if e["event_type"] == "exercised_event"]

def filterDelegateTransactions(transactions: [], actingParties, nonDelegateChoices):
  numTxsWithDelegateChoice = numTxsWithoutDelegateChoice = 0
  exercisedChoices = set()
  for tx in transactions:
    rootEvents = getExercisedRootEvents(tx)
    if [e for e in rootEvents if actingParties in e["acting_parties"] and e["choice"] not in nonDelegateChoices]: numTxsWithDelegateChoice += 1
    else: numTxsWithoutDelegateChoice += 1
    for e in rootEvents:
      exercisedChoices.add(e["choice"])
  return numTxsWithDelegateChoice, numTxsWithoutDelegateChoice, exercisedChoices

def fetchTransactions(url, afterRecordTime, afterMigrationId, durationHours=3, pageSize=1000):
  headers = {'Content-Type': 'application/json'}
  payload = {
    "page_size": pageSize,
    "after": {
      "after_record_time": afterRecordTime,
      "after_migration_id": afterMigrationId
    }
  }
  response = requests.post(url, headers=headers, json=payload)
  response.raise_for_status()
  transactions = response.json()
  transactions = transactions.get("transactions", [])
  if not transactions:
    print("Response was empty.")
    return []
  return transactions

def printSummary(durationHours, totalNumTxsWithDelegateChoice, totalNumTxsWithoutDelegateChoice, startTime, results):
    print(f"Reached the expected duration of {durationHours} hour(s), stopping.")
    # print(f"Set of all exercised choices {totalExercisedChoices}") # prints all exercised choices names
    totalTxs = totalNumTxsWithDelegateChoice + totalNumTxsWithoutDelegateChoice
    print(f"Fetched {totalTxs} transactions, between {startTime} and {results[-1]["record_time"]}.")
    print(f"There were {totalNumTxsWithDelegateChoice} transactions with delegate choices exercised as root event over a total of {totalTxs} transactions in the network.")
    print(f"Rate: {totalNumTxsWithDelegateChoice/totalTxs * 100} %")
    return

def countDelegateTransactions(url, afterRecordTime, afterMigrationId, delegatePartyId, nonDelegateChoices, pageSize=1000, durationHours=3):
  print(f"Fetching transactions from {url} with migration id {afterMigrationId} over a period of {durationHours} hour(s) from {afterRecordTime}...")
  totalNumTxsWithDelegateChoice = totalNumTxsWithoutDelegateChoice = 0
  totalExercisedChoices = set()
  startTime = datetime.fromisoformat(afterRecordTime.replace("Z", "+00:00"))
  endTime = startTime + timedelta(hours=durationHours)
  while True:
    results = []
    transactions = fetchTransactions(url, afterRecordTime, afterMigrationId, durationHours)
    # end time not reached but empty txs
    if not transactions:
      printSummary(durationHours, totalNumTxsWithDelegateChoice, totalNumTxsWithoutDelegateChoice, startTime, results)
      return
    for tx in transactions:
      recordTime = tx.get("record_time")
      recordTime = datetime.fromisoformat(recordTime.replace("Z", "+00:00"))
      if recordTime > endTime:
        printSummary(durationHours, totalNumTxsWithDelegateChoice, totalNumTxsWithoutDelegateChoice, startTime, results)
        return
      else:
        results.append(tx)
    numTxsWithDelegateChoice, numTxsWithoutDelegateChoice, exercisedChoices = filterDelegateTransactions(results, delegatePartyId, nonDelegateChoices)
    totalNumTxsWithDelegateChoice += numTxsWithDelegateChoice
    totalNumTxsWithoutDelegateChoice += numTxsWithoutDelegateChoice
    totalExercisedChoices.update(exercisedChoices)
    afterRecordTime = transactions[-1]["record_time"]
  return


if __name__ == "__main__":
  nonDelegateChoices = [
    "OpenMiningRound_Fetch",
    "DsoRules_ReceiveSvRewardCoupon",
    "AmuletRules_ComputeFees",
    "DsoRules_ConfirmAction",
    "DsoRules_RequestVote",
    "AmuletRules_Transfer",
    "DsoRules_SubmitStatusReport",
    "AmuletRules_BuyMemberTraffic",
    "ValidatorLicense_RecordValidatorLivenessActivity"
  ]
  # For MainNet
  countDelegateTransactions(
      url = "https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v1/updates",
      afterRecordTime = "2025-05-14T10:00:00Z",
      delegatePartyId = "Digital-Asset-2::12209b21d512c6a7e2f5d215266fe6568cb732caaef7ff04e308f990a652340d3529",
      nonDelegateChoices = nonDelegateChoices,
      afterMigrationId = 2,
      durationHours = 24,
  )
  # For DevNet
  countDelegateTransactions(
      url = "https://scan.sv-2.dev.global.canton.network.digitalasset.com/api/scan/v1/updates",
      afterRecordTime = "2025-05-13T10:00:00Z",
      delegatePartyId = "Digital-Asset-2::12208412efbff0c223cb38c7f75158216fdfffee954b03c5681bc936d56d2cb4f754",
      nonDelegateChoices = nonDelegateChoices,
      afterMigrationId = 1,
      durationHours = 3,
  )
