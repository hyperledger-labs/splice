#!/usr/bin/env bash

###############################################################################
# This script was copied from the Canton repo and slightly adapted for the CN repo.
###############################################################################

set -eo pipefail

LOGFILE="$1"
FILE_WITH_IGNORE_PATTERNS="$2"

# Succeed if logfile does not exist
if [[ ! -f "$LOGFILE" ]]
then
  echo "$LOGFILE does not exist."
  echo "Skipping log file check."
  exit 0
fi

# Get the full path to the .circleci directory
SRCDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"

# Load utility functions
source "$SRCDIR"/common.sh
source "$SRCDIR"/io-utils.sh

# Install the canton log format
REPO_ROOT="$SRCDIR/../.."
lnav -i "$REPO_ROOT/canton-release/canton.lnav.json"
# Check consistency of format
lnav -C

# Load ignore patterns for later use
IGNORE_PATTERNS=$(remove_comment_and_blank_lines < "$FILE_WITH_IGNORE_PATTERNS")

run_lnav() {
  catch ENTRIES ERRORS lnav "$@"
  if [[ -n "$ERRORS" ]]
  then
    err "Failed to run lnav with $*: $ERRORS"
    exit 1
  fi
  if [[ -n "${ENTRIES// }" ]]
  then
    cat <<< "$ENTRIES"
  fi
}

### Output ignored log lines

# Reads ignore patterns from stdin (one pattern per line).
# Outputs log entries matching an ignore pattern.
read_ignored_entries() {
  while IFS= read -r; do
    IGNORE_PATTERN="$REPLY"
    run_lnav -n -c ":set-min-log-level warning" -c ":filter-in $IGNORE_PATTERN" "$LOGFILE"
  done
}

# Read ignored entries
read_ignored_entries <<< "$IGNORE_PATTERNS" |
  # Output ignored entries, succeeding in any case
  output_problems "ignored entries" "$LOGFILE" "0"

### Check for errors and warnings

# Create ignore parameters, i.e., "-c :filter-out PATTERN" for later use
IGNORE_PARAMS=$(sed -e 's/.*/:filter-out &/' -e 'i -c' <<< "$IGNORE_PATTERNS")

find_errors() {
  lnav -n -c ":set-min-log-level warning" "$@" "$LOGFILE"
}

# Find the errors
with_input_as_params find_errors <<< "$IGNORE_PARAMS" |
  # Output errors, failing if there are some
  output_problems "problems" "$LOGFILE"

### Output exceptions

find_exceptions() {
  lnav -n -c ":filter-in (?-i:Exception|Error)(?::\s.*)?$" "$@" "$LOGFILE"
}

# Find exceptions
with_input_as_params find_exceptions <<< "$IGNORE_PARAMS" |
  # Output total number of exceptions
  echo "Total: $(wc -l) lines with stack traces."; echo
