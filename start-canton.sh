#!/usr/bin/env bash
set -eou pipefail

if [ -f "canton.pid" ]; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton.tokens
rm -f canton.ports canton-simtime.ports

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

# Create new databases (one for each node used in `simple-topology-canton-simtime.conf`)
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_directory_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwise_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_da_simtime"

# Start Canton
canton \
    daemon --auto-connect-local --log-level-canton=DEBUG \
    --no-tty -c ./apps/app/src/test/resources/simple-topology-canton.conf -C canton.parameters.ports-file=canton.ports \
    --bootstrap bootstrap-canton.canton &
PID=$!
echo "$PID" > canton.pid

# Start second Canton with simulated time, for time-based tests
canton \
    daemon --auto-connect-local --log-level-canton=DEBUG \
    --no-tty -c ./apps/app/src/test/resources/simple-topology-canton-simtime.conf -C canton.parameters.ports-file=canton-simtime.ports \
    --log-file-name log/canton-simtime.log \
    --bootstrap bootstrap-canton.canton &
PID=$!
echo "$PID" > canton-simtime.pid

# Wait for both Cantons to start
while [ ! -f canton.ports ] || [ ! -f canton-simtime.ports ]; do
    echo "Waiting for Canton instances to start"
    sleep 1;
done
echo "Canton instances started"

# Start toxiproxy server
toxiproxy-server > log/toxi.log 2>&1 &
PID=$!
echo "$PID" > toxi.pid
