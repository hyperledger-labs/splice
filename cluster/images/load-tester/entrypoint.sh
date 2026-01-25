#!/usr/bin/env bash
# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# --- Scenario configuration ---
# Read configuration from the CONFIG environment variable, with sensible defaults.
ADAPTIVE_SCENARIO_ENABLED=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.enabled // false')
MAX_VUS=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.maxVUs // 50')
MIN_VUS=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.minVUs // 0')
SCALE_DOWN_STEP=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.scaleDownStep // 5')

# --- Script State ---
CURRENT_VUS=1
PREVIOUS_FAILED_TRANSFERS=""

cleanup() {
    echo "Shutdown signal received. Stopping k6 gracefully..."
    # Use the k6 stop command to end the test
    k6 stop $K6_PID
    echo "k6 stopped."
}

trap cleanup SIGINT SIGTERM

echo "Starting k6"
k6 \
    --verbose \
    --out experimental-prometheus-rw \
    --env EXTERNAL_CONFIG="$EXTERNAL_CONFIG" \
    --log-format json \
    run "generate-load.js" &

K6_PID=$!

echo "k6 is running with PID: $K6_PID"

# Give k6 a moment to start its API server
sleep 3

# --- Conditionally run the controller loop ---
if [ "$ADAPTIVE_SCENARIO_ENABLED" = "true" ]; then
  echo "Controller loop is enabled and running..."

  while true; do
    # --- 1. Read the cumulative failure count from the k6 API ---
    API_RESPONSE=$(curl -s http://localhost:6565/v1/metrics/transfers_failed)
    CURRENT_FAILS=$(echo "$API_RESPONSE" | jq '.data.attributes.sample.count // 0')

    echo "----------------------------------------"
    printf "Current VUs: %d | Total failures so far: %d\n" "$CURRENT_VUS" "$CURRENT_FAILS"

    # --- 2. Check if this is the first run ---
    if [[ -z "$PREVIOUS_FAILED_TRANSFERS" ]]; then
      echo "First run. Storing initial failure count."
      PREVIOUS_FAILED_TRANSFERS=$CURRENT_FAILS
    else
      # --- 3. This is a subsequent run, so calculate the delta ---
      DELTA=$(( CURRENT_FAILS - PREVIOUS_FAILED_TRANSFERS ))
      printf "Previous failure count was: %d. Delta over last 5 mins: %d\n" "$PREVIOUS_FAILED_TRANSFERS" "$DELTA"

      # --- 4. Act based on the delta ---
      if [ "$DELTA" -eq "0" ]; then
        # If delta is zero and max VUs not reached, scale up with 1
        if [ "$CURRENT_VUS" -lt "$MAX_VUS" ]; then
          echo "✅ No new failures. Scaling up by 1 VU."
          NEW_VUS=$(( CURRENT_VUS + 1 ))
          k6 scale --vus="$NEW_VUS"
          CURRENT_VUS=$NEW_VUS
        else
          echo "👌 No new failures. Already at max VUs ($MAX_VUS)."
        fi

        # --- Wait for 5 minutes before the next check ---
        echo "Sleeping for 5 minutes (300 seconds)..."
        sleep 300
      elif [ "$DELTA" -gt "0" ]; then
        if [ "$CURRENT_VUS" -gt "$MIN_VUS" ]; then
          echo "🔥 New failures detected! Scaling down by ${SCALE_DOWN_STEP} VUs."
          NEW_VUS=$(( CURRENT_VUS - SCALE_DOWN_STEP ))
          if [ "$NEW_VUS" -lt "$MIN_VUS" ]; then NEW_VUS=$MIN_VUS; fi
          k6 scale --vus="$NEW_VUS"
          CURRENT_VUS=$NEW_VUS

          echo "Sleeping for 1 minute..."
          sleep 60
        else
          echo "⚠️ New failures detected, but already at min VUs ($MIN_VUS)."

          echo "Sleeping for 5 minutes (300 seconds)..."
          sleep 300
        fi
      fi

      # --- 5. Save the new value for the next check ---
      PREVIOUS_FAILED_TRANSFERS=$CURRENT_FAILS
    fi
  done
else
    echo "Adaptive scenario controller is disabled. The k6 test will run without scaling adjustments."
fi

wait "$K6_PID"
