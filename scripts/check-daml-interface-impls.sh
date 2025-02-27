#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

source "${TOOLS_LIB}/libcli.source"

if rg -P 'Impl this (?!(self|_self))' -g '!scripts'
then
    _error "Interface choices should always pass self"
fi
