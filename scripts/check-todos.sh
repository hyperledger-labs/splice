#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -e

SPLICE_ROOT=$( git rev-parse --show-toplevel )

# Call the ammonite repl with the todo checker Scala script
(cd "$SPLICE_ROOT"; amm scripts/todo/src/checkTodos.sc)

echo ""
echo "See $SPLICE_ROOT/todo-out/ for the summary of the todo check."
