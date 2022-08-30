#!/usr/bin/env bash
set -eou pipefail

POSTGRES_MODE=${1:-docker}

if [ -f "canton.pid" ]; then
    PID=$(cat canton.pid)
    kill "$PID"
    rm canton.pid
else
    echo "The file canton.pid does not exist, not stopping Canton"
fi

./scripts/postgres.sh "$POSTGRES_MODE" stop
