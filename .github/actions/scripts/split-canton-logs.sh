#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

###############################################################################
# Split Canton logs into logs before and after shutdown
###############################################################################



set -eou pipefail

LOGFILE="$1"
LOGFILE_BEFORE="$2"
LOGFILE_AFTER="$3"

# We're using this rather specific pattern to avoid skipping over logs.
SHUTDOWN_MESSAGE_PATTERN='"message":"Shutting down\.\.\.","logger_name":"c\.d\..*\.Canton.*App$"'

# Succeed if logfile does not exist
if [[ ! -f "$LOGFILE" ]]
then
  echo "$LOGFILE does not exist."
  echo "Skipping splitting of log file."
  exit 0
fi

# Fail if the shutdown pattern is present more than once
NUM_SHUTDOWN_PATTERNS=$(grep --count --max-count=2 -E "$SHUTDOWN_MESSAGE_PATTERN" "$LOGFILE" || true)
if [[ "2" == "$NUM_SHUTDOWN_PATTERNS" ]]
then
  # This error will be picked up by the sbt output checker
  echo "ERROR - not splitting the log-files, as there are two or more shutdown messages. See:"
  grep "$SHUTDOWN_MESSAGE_PATTERN" "$LOGFILE"
  exit 2
fi

if [[ -f "$LOGFILE_BEFORE" ]]
then
  # Using range addresses to split the file: https://www.gnu.org/software/sed/manual/sed.html#Range-Addresses
  # This will print everything up to and including the shutdown log line.
  sed -n "0,/$SHUTDOWN_MESSAGE_PATTERN/p" "$LOGFILE" >> "$LOGFILE_BEFORE"
fi

# This will print the shutdown log line and all lines until the end of the file.
if [[ -f "$LOGFILE_AFTER" ]]
then
  sed -n "/$SHUTDOWN_MESSAGE_PATTERN/,\$p" "$LOGFILE" >> "$LOGFILE_AFTER"
fi

# Delete original logfile to ensure every log-line is present in at most one file
rm -f "$LOGFILE"
