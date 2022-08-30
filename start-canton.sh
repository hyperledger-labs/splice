#!/usr/bin/env bash
set -eou pipefail
rm -f canton.ports
SDK_VERSION=2.4.0-snapshot.20220801.10312.0.d2c7be9d
CANTON_VERSION=20220802

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

# Start Canton
if [ ! -d canton-release ]; then
    echo "No canton release in canton-release, downloading"
    mkdir canton-release
    curl -sSL https://github.com/digital-asset/daml/releases/download/${SDK_VERSION}/canton-open-source-${CANTON_VERSION}.tar.gz | tar xz --strip-components=1 -C canton-release
fi
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
