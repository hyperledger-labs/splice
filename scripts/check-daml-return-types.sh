#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

## Check that all Daml choice return types follow the pattern that choice `X` returns type `XResult`, except for:
# - tests
# - splitwell models
# TODO (DACH-NY/canton-network-node#17133): remove the exclude of TradingApp
# - token-standard trading app example
# - `AmuletRules_Transfer` returning `TransferResult` which seems acceptable to keep, given how common it is in the codebase
# - WalletAppInstall choices proxying token standard choices
outliers=$(rg -IN -e "^[ \\t]*(nonconsuming )?choice " -g '!daml/splitwell' -g '!daml/*test' -g '!daml/token-standard/tests/daml/Util/TradingApp.daml' "${SPLICE_ROOT}"/daml/  | sed 's/\(nonconsuming \)\?choice //g' | sed 's/ //g' | awk 'BEGIN {FS = ":"} ; {if ($2 != $1"Result" && $1 != $2"_Fetch" && $1 != "AmuletRules_Transfer" && $1 != "WalletAppInstall_TransferFactory_Transfer" && $1 != "WalletAppInstall_TransferInstruction_Accept" && $1 != "WalletAppInstall_TransferInstruction_Reject" && $1 != "WalletAppInstall_TransferInstruction_Withdraw" && $1 != "WalletAppInstall_AllocationFactory_Allocate" && $1 != "WalletAppInstall_Allocation_Withdraw") print $0 }')
if [ -n "$outliers" ]; then
  _error "The following Daml choices have invalid return types:\n$outliers"
fi
