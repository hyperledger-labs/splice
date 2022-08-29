#!/usr/bin/env bash

###############################################################################
# This script was copied from the Canton repo.
###############################################################################

err() {
  (>&2 echo "ERROR: $1")
}

warn() {
  (>&2 echo "WARN: $1")
}

info() {
  (>&2 echo "INFO: $1")
}

debug() {
  [ -z "$DEBUG" ] || (>&2 echo "DEBUG: $1")
}

# Helper function to run commands with debug and error logging
# Failed commands will exit the process with a 1 exit code
# First parameter should be message to use for logging
# Remaining arguments are passed to the subshell to run the command
# Usage: run "Listing some directory" ls dir-name
run() {
  local message="$1"
  shift
  local -ra cmd=( "$@" )
  local output

  debug "$message [${cmd[*]}]"

  if ! output=$("$@" 2>&1); then
    err "$message failed: $output"
    exit 1
  fi
}

