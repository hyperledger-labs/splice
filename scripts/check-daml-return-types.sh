#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

## Check that all Daml choice return types follow the pattern that choice `X` returns type `XResult`, except for:
# - tests
# - splitwell models
# - token standard APIs and tests
# - `AmuletRules_Transfer` returning `TransferResult` which seems acceptable to keep, given how common it is in the codebase
# - splice-util-featured-app-proxies, which use `ProxyResult <iface_result>`
# - WalletAppInstall choices proxying token standard choices
outliers=$(rg -IN -e \
  "^[ \\t]*(nonconsuming )?choice " \
  -g '!daml/splitwell' \
  -g '!daml/*test' \
  -g '!daml/splice-api-token-burn-mint-v1' \
  -g '!daml/splice-util-featured-app-proxies' \
  "${SPLICE_ROOT}"/daml/  | \
  sed 's/\(nonconsuming \)\?choice //g' | \
  sed 's/ //g' | \
  awk 'BEGIN {FS = ":"} ; {if ($2 != $1"Result" && $1 != $2"_Fetch" && $1 != "AmuletRules_Transfer" && $1 != "WalletAppInstall_TransferFactory_Transfer" && $1 != "WalletAppInstall_TransferInstruction_Accept" && $1 != "WalletAppInstall_TransferInstruction_Reject" && $1 != "WalletAppInstall_TransferInstruction_Withdraw" && $1 != "WalletAppInstall_AllocationFactory_Allocate" && $1 != "WalletAppInstall_Allocation_Withdraw") print $0 }' \
  )
if [ -n "$outliers" ]; then
  _error "The following Daml choices have invalid return types:\n$outliers"
fi
