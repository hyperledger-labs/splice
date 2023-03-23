#!/usr/bin/env bash

set -e

REPO_ROOT=$( git rev-parse --show-toplevel )


# Call the ammonite repl with the todo checker Scala script
(cd "$REPO_ROOT"; amm .circleci/todo-script/src/checkTodos.sc)

echo ""
echo "See $REPO_ROOT/todo-out/ for the summary of the todo check."
