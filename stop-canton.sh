#!/usr/bin/env bash
set -eou pipefail

POSTGRES_MODE=${1:-docker}

if [ -f "toxi.pid" ]; then
  PID=$(cat toxi.pid)
  kill "$PID" || true
  rm toxi.pid
fi

if [ -f "canton.pid" ]; then
    PID=$(cat canton.pid)
    kill "$PID" || true
    rm canton.pid
else
    echo "The file canton.pid does not exist, not stopping Canton"
fi

if [ -f "canton-simtime.pid" ]; then
    PID=$(cat canton-simtime.pid)
    kill "$PID" || true
    rm canton-simtime.pid
else
    echo "The file canton-simtime.pid does not exist, not stopping simtime Canton"
fi

./scripts/postgres.sh "$POSTGRES_MODE" stop
