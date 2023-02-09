#!/usr/bin/env bash
set -eou pipefail

if [ -f "canton.pid" ]; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton.tokens canton-simtime.tokens
rm -f canton.ports canton-simtime.ports

POSTGRES_MODE=${1:-docker}

# Start Postgres
./scripts/postgres.sh "$POSTGRES_MODE" start

# Create new databases (one for each node used in `simple-topology-canton.conf`)
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_directory"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwell"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_global"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell"

# Create new databases (one for each node used in `simple-topology-canton-simtime.conf`)
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_directory_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwell_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_global_simtime"
./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell_simtime"

# TODO(#1836) Avoid having to inject our patched auth service.
sbt --batch canton-community-participant/compile
# We only want one file in the classpath so create a separate directory rather than
# pointing directly to the SBT output dir.
rm -rf canton-classpath
mkdir -p canton-classpath/com/digitalasset/canton/participant/ledger/api
cp ./canton/community/participant/target/scala-2.13/classes/com/digitalasset/canton/participant/ledger/api/CantonAdminTokenAuthService.class \
   ./canton-classpath/com/digitalasset/canton/participant/ledger/api
export CLASSPATH=$PWD/canton-classpath

# Start Canton
CANTON_TOKEN_FILENAME=canton.tokens canton \
    daemon --no-tty \
    -c ./apps/app/src/test/resources/simple-topology-canton.conf \
    -C canton.parameters.ports-file=canton.ports \
    --log-level-canton=DEBUG \
    --log-encoder json \
    --log-file-name log/canton.clog \
    --bootstrap bootstrap-canton.sc &
PID=$!
echo "$PID" > canton.pid

# Start second Canton with simulated time, for time-based tests
CANTON_TOKEN_FILENAME=canton-simtime.tokens canton \
    daemon --no-tty \
    -c ./apps/app/src/test/resources/simple-topology-canton-simtime.conf \
    -C canton.parameters.ports-file=canton-simtime.ports \
    --log-level-canton=DEBUG \
    --log-encoder json \
    --log-file-name log/canton-simtime.clog \
    --bootstrap bootstrap-canton.sc &
PID=$!
echo "$PID" > canton-simtime.pid

# Wait for both Cantons to start
while [ ! -f canton.tokens ] || [ ! -f canton-simtime.tokens ]; do
    echo "Waiting for Canton instances to start"
    sleep 1;
done
echo "Canton instances started"

# Start toxiproxy server
toxiproxy-server > log/toxi.log 2>&1 &
PID=$!
echo "$PID" > toxi.pid
