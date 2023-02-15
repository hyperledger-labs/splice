#!/usr/bin/env bash
set -eou pipefail

POSTGRES_MODE=${1:-docker}

tmux kill-session -t canton || true

rm -f canton.tokens canton-simtime.tokens

./scripts/postgres.sh "$POSTGRES_MODE" stop
