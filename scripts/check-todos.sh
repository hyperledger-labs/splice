#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -e

REPO_ROOT=$( git rev-parse --show-toplevel )


# Call the ammonite repl with the todo checker Scala script
(cd "$REPO_ROOT"; amm .circleci/todo/src/checkTodos.sc)

echo ""
echo "See $REPO_ROOT/todo-out/ for the summary of the todo check."
