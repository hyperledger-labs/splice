#!/usr/bin/env bash
set -eou pipefail

if [ -f "canton.pid" ]; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton.ports

POSTGRES_MODE=${1:-docker}

# Start Postgres
./scripts/postgres.sh "$POSTGRES_MODE" start

# Create new databases (one for each node used in `simple-topology-canton.conf`)
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_directory"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwise"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_da"

# Download Canton
./download-canton.sh

# Start Canton
./canton-release/bin/canton \
    daemon --auto-connect-local --log-level-canton=DEBUG \
    --no-tty -c ./apps/app/src/test/resources/simple-topology-canton.conf -C canton.parameters.ports-file=canton.ports &
PID=$!
echo "$PID" > canton.pid

# Wait for Canton
while [ ! -f canton.ports ]; do
    echo "Waiting for Canton to start"
    sleep 1;
done
echo "Canton started"
