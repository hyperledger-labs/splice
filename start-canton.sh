#!/usr/bin/env bash
set -eou pipefail

tmux_session="canton"
tmux_window=0

if tmux has-session -t $tmux_session 2>/dev/null; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton.tokens canton-simtime.tokens

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

function tmux_cmd() {
  local title=$1
  local cmd=$2
  local t=${tmux_session}:${tmux_window}

  if [[ ${tmux_window} -eq 0 ]]; then
    tmux rename-window -t "$t" "$title"
  else
    tmux new-window -t "$t" -n "$title"
  fi
  tmux send-keys -t "$t" "$cmd" C-m
  tmux_window=$((tmux_window+1))
}

tmux new-session -d -s "${tmux_session}"

tmux_cmd canton-wallclocktime \
  "CANTON_TOKEN_FILENAME=canton.tokens canton \
    -c ./apps/app/src/test/resources/simple-topology-canton.conf \
    --log-level-canton=DEBUG \
    --log-encoder json \
    --log-file-name log/canton.clog \
    --bootstrap bootstrap-canton.sc"

tmux_cmd canton-simtime \
  "CANTON_TOKEN_FILENAME=canton-simtime.tokens canton \
    -c ./apps/app/src/test/resources/simple-topology-canton-simtime.conf \
    --log-level-canton=DEBUG \
    --log-encoder json \
    --log-file-name log/canton-simtime.clog \
    --bootstrap bootstrap-canton.sc"

# Wait for both Cantons to start
while [ ! -f canton.tokens ] || [ ! -f canton-simtime.tokens ]; do
    echo "Waiting for Canton instances to start"
    sleep 1;
done
echo "Canton instances started"

tmux_cmd toxiproxy toxiproxy-server > log/toxi.log 2>&1
