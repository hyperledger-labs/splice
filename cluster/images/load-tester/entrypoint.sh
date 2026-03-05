#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# --- Scenario configuration ---
# Read configuration from the CONFIG environment variable, with sensible defaults.
ADAPTIVE_SCENARIO_ENABLED=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.enabled // false')
MAX_VUS=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.maxVUs // 50')
MIN_VUS=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.minVUs // 0')
SCALE_DOWN_STEP=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.scaleDownStep // 5')
SCALE_UP_STEP=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.scaleUpStep // 2')
WINDOW_START_UTC=$(echo "$EXTERNAL_CONFIG" | jq -r '.adaptiveScenario.windowStartUTC // "03:00"')
WINDOW_DURATION_MINUTES=$(echo "$EXTERNAL_CONFIG" | jq '.adaptiveScenario.windowDurationMinutes // 120')

# --- Window helpers ---
# Returns 0 (true) if the current UTC time is inside the configured window.
is_inside_window() {
  local start_hour start_minute
  start_hour=$(echo "$WINDOW_START_UTC" | cut -d: -f1)
  start_minute=$(echo "$WINDOW_START_UTC" | cut -d: -f2)

  local now_epoch window_start_epoch window_end_epoch
  now_epoch=$(date -u +%s)
  window_start_epoch=$(date -u -d "$(date -u +%Y-%m-%d) ${start_hour}:${start_minute}:00" +%s)
  window_end_epoch=$(( window_start_epoch + WINDOW_DURATION_MINUTES * 60 ))

  # Handle case where the window started yesterday and hasn't ended yet
  # (e.g., window starts at 23:00 for 180 min, it's now 01:00)
  local yesterday_start_epoch yesterday_end_epoch
  yesterday_start_epoch=$(( window_start_epoch - 86400 ))
  yesterday_end_epoch=$(( yesterday_start_epoch + WINDOW_DURATION_MINUTES * 60 ))

  if [ "$now_epoch" -ge "$window_start_epoch" ] && [ "$now_epoch" -lt "$window_end_epoch" ]; then
    return 0
  elif [ "$now_epoch" -ge "$yesterday_start_epoch" ] && [ "$now_epoch" -lt "$yesterday_end_epoch" ]; then
    return 0
  else
    return 1
  fi
}

# Prints the number of seconds until the next window opens.
seconds_until_window() {
  local start_hour start_minute
  start_hour=$(echo "$WINDOW_START_UTC" | cut -d: -f1)
  start_minute=$(echo "$WINDOW_START_UTC" | cut -d: -f2)

  local now_epoch window_start_epoch
  now_epoch=$(date -u +%s)
  window_start_epoch=$(date -u -d "$(date -u +%Y-%m-%d) ${start_hour}:${start_minute}:00" +%s)

  if [ "$window_start_epoch" -le "$now_epoch" ]; then
    window_start_epoch=$(( window_start_epoch + 86400 ))
  fi

  echo $(( window_start_epoch - now_epoch ))
}

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
  echo "Adaptive window: ${WINDOW_START_UTC} UTC for ${WINDOW_DURATION_MINUTES} minutes."

  while true; do
    # --- Check if we are inside the active window ---
    if ! is_inside_window; then
      # Outside the window: scale adaptive VUs to 0 and wait
      if [ "$CURRENT_VUS" -gt 0 ]; then
        echo "Outside adaptive window. Scaling adaptive VUs to 0."
        k6 scale --vus=0
        CURRENT_VUS=0
        PREVIOUS_FAILED_TRANSFERS=""
      fi

      WAIT_SECS=$(seconds_until_window)
      echo "Next window opens at ${WINDOW_START_UTC} UTC. Waiting ${WAIT_SECS} seconds ($(( WAIT_SECS / 3600 ))h $(( (WAIT_SECS % 3600) / 60 ))m)..."
      sleep "$WAIT_SECS"

      # Start fresh at 1 VU when the window opens
      echo "Adaptive window opened. Starting ramp-up."
      CURRENT_VUS=1
      k6 scale --vus=1
      PREVIOUS_FAILED_TRANSFERS=""
      continue
    fi

    # --- Inside the window: run adaptive scaling logic ---

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
        # If delta is zero and max VUs not reached, scale up
        if [ "$CURRENT_VUS" -lt "$MAX_VUS" ]; then
          echo "No new failures. Scaling up by ${SCALE_UP_STEP} VU(s)."
          NEW_VUS=$(( CURRENT_VUS + SCALE_UP_STEP ))
          if [ "$NEW_VUS" -gt "$MAX_VUS" ]; then NEW_VUS=$MAX_VUS; fi
          k6 scale --vus="$NEW_VUS"
          CURRENT_VUS=$NEW_VUS
        else
          echo "No new failures. Already at max VUs ($MAX_VUS)."
        fi

        # --- Wait for 5 minutes before the next check ---
        echo "Sleeping for 5 minutes (300 seconds)..."
        sleep 300
      elif [ "$DELTA" -gt "0" ]; then
        if [ "$CURRENT_VUS" -gt "$MIN_VUS" ]; then
          echo "New failures detected! Scaling down by ${SCALE_DOWN_STEP} VUs."
          NEW_VUS=$(( CURRENT_VUS - SCALE_DOWN_STEP ))
          if [ "$NEW_VUS" -lt "$MIN_VUS" ]; then NEW_VUS=$MIN_VUS; fi
          k6 scale --vus="$NEW_VUS"
          CURRENT_VUS=$NEW_VUS

          echo "Sleeping for 1 minute..."
          sleep 60
        else
          echo "New failures detected, but already at min VUs ($MIN_VUS)."

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
