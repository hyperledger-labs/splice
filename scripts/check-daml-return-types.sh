#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

## Check that all Daml choice return types follow the pattern that choice `X` returns type `XResult`, except for:
# - tests
# - splitwell models
# - `AmuletRules_Transfer` returning `TransferResult` which seems acceptable to keep, given how common it is in the codebase
outliers=$(rg -IN -e "^[ \\t]*(nonconsuming )?choice " -g '!daml/splitwell' -g '!daml/*test' "${SPLICE_ROOT}"/daml/  | sed 's/\(nonconsuming \)\?choice //g' | sed 's/ //g' | awk 'BEGIN {FS = ":"} ; {if ($2 != $1"Result" && $1 != $2"_Fetch" && $1 != "AmuletRules_Transfer") print $0 }')
if [ -n "$outliers" ]; then
  _error "The following Daml choices have invalid return types:\n$outliers"
fi
