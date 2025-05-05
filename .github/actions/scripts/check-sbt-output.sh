#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eo pipefail

###############################################################################
# This script was copied from the Canton repo and slightly adapted for the CN repo.
###############################################################################


SBT_OUTPUT_FILE="$1"
# Note the files with ignore patterns are hardcoded, as we have only one call-site
# and our preflight checks can lead to mismatches in arguments on the call-site
# and the actual files available in the checkout as of which the preflight check
# runs; see #3528.
FILES_WITH_IGNORE_PATTERNS=("project/ignore-patterns/sbt-output.ignore.txt")

# Get the full path to the .circleci directory
SRCDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"

# Load utility functions
source "$SRCDIR"/io-utils.sh

read_sbt_output() {
  grep -v -E "$@" "$SBT_OUTPUT_FILE"
}

filter_errors() {
  grep -i -e error -e severe -e warn -e warning -e exception -e critical -e fatal || true
}

# Read ignore patterns
cat "${FILES_WITH_IGNORE_PATTERNS[@]}" |
  remove_comment_and_blank_lines |
  # Prepend each pattern with '-e'
  sed 'i -e' |
  # Read sbt output and remove ignored lines
  with_input_as_params read_sbt_output |
  # Detect the errors
  filter_errors |
  # Report errors and fail on error
  output_problems "problems" "the sbt output"
