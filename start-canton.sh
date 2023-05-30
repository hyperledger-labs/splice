#!/usr/bin/env bash
set -eou pipefail

function usage() {
  echo "Usage: ./start-canton.sh <flags>"
  echo "Flags:"
  echo "  -h               display this help message"
  echo "  -d               start in detached mode"
  echo "  -p postgres_mode postgres mode used in scripts/postgres.sh, default 'docker'"
  echo "  -w               only start canton instance with wall clock time"
  echo "  -s               only start canton instance with simulated time"
  echo "  -x               only start the experimental canton x instance (not started by default)"
}

# default values
daemon=0
wallclocktime=1
simtime=1
x=0
POSTGRES_MODE=docker

while getopts "hdap:wsx" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    d)
      daemon=1
      ;;
    p)
      POSTGRES_MODE="${OPTARG}"
      ;;
    w)
      simtime=0
      echo "starting canton with wall clock time only"
      ;;
    s)
      wallclocktime=0
      echo "starting canton with simulated time only"
      ;;
    x)
      x=1
      simtime=0
      wallclocktime=0
      echo "starting canton with x nodes only"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

tmux_session="canton"
tmux_window=0

if tmux has-session -t $tmux_session 2>/dev/null; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton*.tokens

# Start Postgres
./scripts/postgres.sh "$POSTGRES_MODE" start

if [ $wallclocktime -eq 1 ]; then
  # Create new databases (one for each node used in `simple-topology-canton.conf`)
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwell"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv2"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv3"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv4"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_global"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell_upgrade"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "self_hosted_participant"
fi

if [ $simtime -eq 1 ]; then
  # Create new databases (one for each node used in `simple-topology-canton-simtime.conf`)
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_svc_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_bob_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_splitwell_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv2_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv3_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv4_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_global_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell_simtime"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "domain_splitwell_upgrade_simtime"
fi

if [ $x -eq 1 ]; then
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv1_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv2_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv3_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_sv4_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "sequencer_driver"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "sequencer_sv1_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "sequencer_sv2_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "sequencer_sv3_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "sequencer_sv4_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "mediator_sv1_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "mediator_sv2_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "mediator_sv3_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "mediator_sv4_x"
  ./scripts/postgres.sh "$POSTGRES_MODE" createdb "participant_alice_x"
fi

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

# Numbers chosen such that we don't run out of memory and CI runs are not measurably slower.
# Feel free to bump if you encounter issues but make sure the nodes don't run out of memory.
JAVA_TOOL_OPTIONS="-Xms4g -Xmx4g -Dlogback.configurationFile=./scripts/canton-logback.xml"

if [ $wallclocktime -eq 1 ]; then
  tmux_cmd canton-wallclocktime \
    "CANTON_TOKEN_FILENAME=canton.tokens JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" canton \
      -c ./apps/app/src/test/resources/simple-topology-canton.conf \
      --log-level-canton=DEBUG \
      --log-encoder json \
      --log-file-name log/canton.clog \
      --bootstrap bootstrap-canton.sc"
fi

if [ $simtime -eq 1 ]; then
  tmux_cmd canton-simtime \
    "CANTON_TOKEN_FILENAME=canton-simtime.tokens JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\"  canton \
      -c ./apps/app/src/test/resources/simple-topology-canton-simtime.conf \
      --log-level-canton=DEBUG \
      --log-encoder json \
      --log-file-name log/canton-simtime.clog \
      --bootstrap bootstrap-canton.sc"
fi

if [ $x -eq 1 ]; then
  # For now we reuse canton.tokens here which makes it not possible to run the wallclock canton and X node canton at the same time.
  tmux_cmd canton-x \
    "CANTON_TOKEN_FILENAME=canton.tokens JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" canton \
      -c ./apps/app/src/test/resources/simple-topology-canton-x.conf \
      --log-level-canton=DEBUG \
      --log-encoder json \
      --log-file-name log/canton-x.clog \
      --bootstrap bootstrap-canton-x.sc"
fi

# Wait for both Cantons to start
while { [ $wallclocktime -eq 1 ] && [ ! -f canton.tokens ]; } || { [ $simtime -eq 1 ] && [ ! -f canton-simtime.tokens ]; } || { [ $x -eq 1 ] && [ ! -f canton.tokens ]; } ; do
    echo "Waiting for Canton instance(s) to start"
    sleep 1;
done
echo "Canton instance(s) started"

tmux_cmd toxiproxy toxiproxy-server > log/toxi.log 2>&1

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to canton terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
