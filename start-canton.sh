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
  echo "  -b               start canton to test BFT domain operations. In that case, the participants are not automatically connected to the domain"
  echo "  -t               start canton with traffic control enabled"
  echo "  -c <canton>      start a custom canton binary instead of the one on the PATH"
}

# default values
daemon=0
wallclocktime=1
simtime=1
POSTGRES_MODE=docker
CANTON=canton
bft=0
trafficQoS=0
bootstrapScriptPath=bootstrap-canton.sc

while getopts "hdap:c:wsbt" arg; do
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
    c)
      CANTON="${OPTARG}"
      echo "using custom canton binary: $CANTON"
      ;;
    b)
      bft=1
      echo "start canton to test bft domain, not connecting participants to global domain"
      ;;
    t)
      trafficQoS=1
      echo "start canton with traffic control enabled"
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

db_names=()
if [ $wallclocktime -eq 1 ]; then
  db_names+=(
    "participant_sv1"
    "participant_sv2"
    "participant_sv3"
    "participant_sv4"
    "sequencer_driver"
    "sequencer_driver_splitwell"
    "sequencer_driver_splitwell_upgrade"
    "sequencer_sv1"
    "sequencer_sv2"
    "sequencer_sv3"
    "sequencer_sv4"
    "sequencer_splitwell"
    "sequencer_splitwell_upgrade"
    "mediator_sv1"
    "mediator_sv2"
    "mediator_sv3"
    "mediator_sv4"
    "mediator_splitwell"
    "mediator_splitwell_upgrade"
    "participant_alice"
    "participant_bob"
    "participant_splitwell"
    "self_hosted_participant"
  )
fi

if [ $simtime -eq 1 ]; then
  db_names+=(
    "participant_sv1_simtime"
    "participant_sv2_simtime"
    "participant_sv3_simtime"
    "participant_sv4_simtime"
    "sequencer_driver_simtime"
    "sequencer_driver_splitwell_simtime"
    "sequencer_driver_splitwell_upgrade_simtime"
    "sequencer_sv1_simtime"
    "sequencer_sv2_simtime"
    "sequencer_sv3_simtime"
    "sequencer_sv4_simtime"
    "sequencer_splitwell_simtime"
    "sequencer_splitwell_upgrade_simtime"
    "mediator_sv1_simtime"
    "mediator_sv2_simtime"
    "mediator_sv3_simtime"
    "mediator_sv4_simtime"
    "mediator_splitwell_simtime"
    "mediator_splitwell_upgrade_simtime"
    "participant_alice_simtime"
    "participant_bob_simtime"
    "participant_splitwell_simtime"
  )
fi

# Create the DB's in parallel
printf '%s\n' "${db_names[@]}" | xargs -P 64 -I {} ./scripts/postgres.sh "$POSTGRES_MODE" createdb {}

# Tmux session setup
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

config_overrides=""
if [ $trafficQoS -eq 1 ]; then
  config_overrides="$config_overrides -c ./apps/app/src/test/resources/domain-fees-overrides.conf"
fi

if [ $wallclocktime -eq 1 ]; then
 tmux_cmd canton \
   "CANTON_TOKEN_FILENAME=canton.tokens BFT=\"$bft\" JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" $CANTON \
     -c ./apps/app/src/test/resources/simple-topology-canton.conf $config_overrides \
     --log-level-canton=DEBUG \
     --log-encoder json \
     --log-file-name log/canton.clog \
     --bootstrap $bootstrapScriptPath"
fi

if [ $simtime -eq 1 ]; then
 tmux_cmd canton-simtime \
   "CANTON_TOKEN_FILENAME=canton-simtime.tokens BFT=\"$bft\" JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" $CANTON \
     -c ./apps/app/src/test/resources/simple-topology-canton-simtime.conf $config_overrides \
     --log-level-canton=DEBUG \
     --log-encoder json \
     --log-file-name log/canton-simtime.clog \
     --bootstrap $bootstrapScriptPath"
fi


# Wait for canton instance(s) to start within 5 minutes
timeout=300
start_time=$(date +%s)
while (( $(date +%s) - start_time < timeout )); do
    if { [ $wallclocktime -eq 1 ] && [ ! -f canton.tokens ]; } || { [ $simtime -eq 1 ] && [ ! -f canton-simtime.tokens ]; }; then
        remaining_time=$((timeout - ($(date +%s) - start_time)))
        echo "Waiting for Canton instance(s) to start (${remaining_time} seconds left)"
        sleep 1
    else
        echo "Canton instance(s) started"
        break
    fi
done

if (( $(date +%s) - start_time >= timeout )); then
    echo "Timeout: Canton instance(s) failed to start within $timeout seconds"
    exit 1
fi


tmux_cmd toxiproxy toxiproxy-server > log/toxi.log 2>&1

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to canton terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
