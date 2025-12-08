#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

###############################################################################
# This script was copied from the Canton repo and then heavily adapted to use
# ripgrep instead of lnav for filtering the logs. To say that it's faster this
# way is an understatement.
###############################################################################



set -eou pipefail

LOGFILE="$1"
shift
FILES_WITH_IGNORE_PATTERNS=("$@")

# Succeed if logfile does not exist
if [[ ! -f "$LOGFILE" ]]
then
  echo "$LOGFILE does not exist."
  echo "Skipping log file check."
  echo ""
  exit 0
else
  echo "Checking $LOGFILE while ignoring: ${FILES_WITH_IGNORE_PATTERNS[*]}"
fi

# Get the full path to the script directory
SRCDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"

# Load utility functions
source "$SRCDIR"/io-utils.sh

# Load ignore patterns for later use
IGNORE_PATTERNS="$(cat "${FILES_WITH_IGNORE_PATTERNS[@]}" | remove_comment_and_blank_lines)"

# Keywords are based on `canton-json.lnav.json`
MIN_LOG_LEVEL_WARNING="\"level\":\"WARN\"|\"level\":\"ERROR\""

### Output ignored log lines

# Reads ignore patterns from stdin (one pattern per line).
# Outputs log entries matching an ignore pattern.
read_ignored_entries() {
  rg -f - <<< "$IGNORE_PATTERNS" "$LOGFILE" |
    rg -e "$MIN_LOG_LEVEL_WARNING" || true
}

# Read ignored entries
read_ignored_entries |
  # We print the ignored entries as part of our sbt run, and we don't want them to trigger the sbt output checker.
  # We thus add this suffix which is ignored by default in the sbt output checker.
  sed 's/$/ (ignore this line in check-sbt-output.sh)/' |
  # Output ignored entries, succeeding in any case
  output_problems "ignored entries" "$LOGFILE" "0"

### Check for errors and warnings

# this differs from `read_ignored_entries` by one `-v`
find_errors() {
  # rg returns 1 if there were not matches so we add the || true
  rg -v -f - <<< "$IGNORE_PATTERNS" "$LOGFILE" |
    rg -e "$MIN_LOG_LEVEL_WARNING" || true
}

# Find the errors
find_errors |
  # Output errors, failing if there are some
  output_problems "problems" "$LOGFILE"

### Output exceptions

find_exceptions() {
  set +o pipefail # rg returns 1 if there were not matches
  rg -v -f - <<< "$IGNORE_PATTERNS" "$LOGFILE" |
    rg -e "(?-i:Exception|Error)(?::\s.*)?$" || true
    # not replacing anything here on purpose as these log lines
    # might have different structure; better to avoid removing
    # anything we might need
}

# Find exceptions
find_exceptions |
  # Output total number of exceptions
  echo "Total: $(wc -l) lines with stack traces."; echo

### Look for leaked secrets

# TODO(DACH-NY/canton-network-internal#481) Patch secrets in the log file
sed -i 's/secret=test/secret=hidden/g' "$LOGFILE"

find_secrets() {
  set +o pipefail # rg returns 1 if there were no matches
  # Common x=y format
  rg -o -e "(secret|token|private-key|password)=[^,[:space:]]*" "$LOGFILE" |
    # we mask secrets as "****" in our logs and testcontainers obfuscates secrets as "hidden non-blank value"
    # (https://github.com/testcontainers/testcontainers-java/blob/bf5605a2031d7f29f86a85430e3509a198c6e125/core/src/main/java/org/testcontainers/utility/AuthConfigUtil.java#L33)
    rg -v -e "=\\\\\"\*\*\*\*\\\\\"" -e "=hidden" || true
  # JWTs; `eyJhbGc` is a base64-endcoded JSON object that starts with `{"alg`
  rg -o -e "(Bearer\s*$|(Bearer\s*e|eyJhbGc)[A-Za-z0-9\-\_]{2,}\.[A-Za-z0-9\-\_]{2,}\.[A-Za-z0-9\-\_]{2,})" "$LOGFILE" || true
}

# Find leaked secrets
find_secrets |
  # Output unmasked secrets, failing if there are some
  output_problems "unmasked secrets" "$LOGFILE"

find_deprecated_configs() {
  set +o pipefail # rg returns 1 if there were not matches
  rg -v -f - <<< "$IGNORE_PATTERNS" "$LOGFILE" |
    rg -e 'Config field at (\S+) is deprecated' || true
}

find_deprecated_configs |
  output_problems "deprecated config paths" "$LOGFILE"

# Give the next command some space
echo ""
