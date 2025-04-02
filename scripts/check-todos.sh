#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -e
# hub CLI used in checkTodos.sc requires GITHUB_USER and GITHUB_TOKEN
export GITHUB_TOKEN=$GH_TOKEN
export GITHUB_USER=$GH_USER

SPLICE_ROOT=$( git rev-parse --show-toplevel )

# Call the ammonite repl with the todo checker Scala script
(cd "$SPLICE_ROOT"; amm .circleci/todo/src/checkTodos.sc)

echo ""
echo "See $SPLICE_ROOT/todo-out/ for the summary of the todo check."
