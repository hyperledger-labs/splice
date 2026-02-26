#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

function usage() {
  echo "Usage: ./start-canton.sh <flags>"
  echo "Flags:"
  echo "  -h               display this help message"
  echo "  -d               start in detached mode"
  echo "  -D               don't even wait for Canton to start before going on detached mode (implies -d)"
  echo "  -b               start in the background"
  echo "  -p postgres_mode postgres mode used in scripts/postgres.sh, default 'docker'"
  echo "  -w               only start canton instance with wall clock time"
  echo "  -s               only start canton instance with simulated time"
  echo "  -f               start canton using the CometBFT driver for the global sequencers"
  echo "  -F               same as -f, but does not start cometBFT, but rather assumes it is already running"
  echo "  -e               start canton using the canton provided BFT sequencer"
  echo "  -m               collect metrics and send them to our CI prometheus instance"
  echo "  -c <canton>      start a custom canton binary instead of the one on the PATH"
  echo "  -B <script>      path to a custom canton bootstrap script"
}

# default values
daemon=0
wallclocktime=1
simtime=1
POSTGRES_MODE=docker
CANTON=canton
bootstrapScriptPath=bootstrap-canton.sc
start_cometbft=0
use_cometbft=0
use_bft=0
collect_metrics=0
logFileHint=canton

args=$(getopt -o "hdDap:cB:wsbtfFegm" -l "help" -- "$@")

eval set -- "$args"

while true
do
    case "$1" in
        -h)
            usage
            exit 0
            ;;
        --help)
            usage
            exit 0
            ;;
        -D)
            daemon=3
            ;;
        -b)
            daemon=2
            ;;
        -d)
            daemon=1
            ;;
        -p)
            POSTGRES_MODE="$2"
            shift
            ;;
        -w)
            simtime=0
            echo "starting canton with wall clock time only"
            ;;
        -s)
            wallclocktime=0
            echo "starting canton with simulated time only"
            ;;
        -c)
            CANTON="$2"
            shift
            echo "using custom canton binary: $CANTON"
            ;;
        -f)
            start_cometbft=1
            use_cometbft=1
            echo "start canton with the cometbft driver (and start CometBFT)"
            ;;
        -F)
            start_cometbft=0
            use_cometbft=1
            echo "start canton with the cometbft driver (assuming CometBFT is already running)"
            ;;
        -e)
            use_bft=1
            echo "start canton with the bft sequencer"
            ;;
        -m)
            collect_metrics=1
            ;;
        -B)
            bootstrapScriptPath="$2"
            logFileHint=canton-missing-signatures
            shift
            echo "using a custom canton bootstrap script: $bootstrapScriptPath"
            ;;
        --)
            shift
            break
            ;;
    esac
    shift
done

tmux_session="canton"
tmux_window=0

if tmux has-session -t=$tmux_session 2>/dev/null; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton*.tokens

# Start Postgres
./scripts/postgres.sh "$POSTGRES_MODE" start

# Start CometBFT
if [[ $start_cometbft -eq 1 ]]; then
  # Sourcing this to get exported variables.
  . scripts/cometbft.sh start
fi;

any_time_db_names=(
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
  "sequencer_sv1_bft"
  "sequencer_sv2_bft"
  "sequencer_sv3_bft"
  "sequencer_sv4_bft"
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
  "sequencer_driver_global_reonboard"
)

db_names=()
if [ $wallclocktime -eq 1 ]; then
  db_names+=(
    "${any_time_db_names[@]}"
  )
fi

if [ $simtime -eq 1 ]; then
  # same names, but with _simtime suffix
  IFS=' ' read -r -a simtime_db_names <<< \
      "$(echo "${any_time_db_names[@]}" | sed -Ee 's/( |$)/_simtime\1/g')"
  db_names+=("${simtime_db_names[@]}")
fi

# Create the DB's in parallel
printf '%s\n' "${db_names[@]}" | xargs -P 64 -I {} ./scripts/postgres.sh "$POSTGRES_MODE" createdb {}

echo "Creating DB for CN apps"
./scripts/postgres.sh "$POSTGRES_MODE" createdb splice_apps

# Tmux session setup
function tmux_cmd() {
  title=$1
  cmd=$2
  t=${tmux_session}:${tmux_window}

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
JAVA_TOOL_OPTIONS="-Xms6g -Xmx8g -Dlogback.configurationFile=./scripts/canton-logback.xml ${ADDITIONAL_JAVA_TOOLS_OPTIONS:-}"

config_overrides=""
config_overrides_simtime=""

if [[ $use_cometbft -eq 1 ]]; then
  config_overrides="$config_overrides -c ./apps/app/src/test/resources/cometbft-sequencer-global-domain-overrides.conf"
fi;

if [[ $use_bft -eq 1 ]]; then
  config_overrides="$config_overrides -c ./apps/app/src/test/resources/bft-sequencer-global-domain-overrides.conf"
fi;


tmux_cmd_canton() {
  windowName="$1" tokensFile="$2" participantsFile="$3" baseConfig="$4" confOverrides="$5" logFileHint="$6"
  mainLogFile="log/${logFileHint}.clog"
  consoleLogFile="log/${logFileHint}.out"
  tmux_cmd "$windowName" \
    "EXTRA_CLASSPATH=$COMETBFT_DRIVER/driver.jar \
     COMETBFT_DOCKER_IP=${COMETBFT_DOCKER_IP-} \
     CANTON_TOKEN_FILENAME=$tokensFile CANTON_PARTICIPANTS_FILENAME=$participantsFile JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" $CANTON \
      -c $baseConfig $confOverrides \
      --log-level-canton=DEBUG \
      --log-encoder json \
      --log-file-name $mainLogFile \
      --bootstrap $bootstrapScriptPath \
      2>&1 | tee -a $consoleLogFile"
}

if [ $wallclocktime -eq 1 ]; then
  tmux_cmd_canton canton canton.tokens canton.participants \
    ./apps/app/src/test/resources/simple-topology-canton.conf \
    "$config_overrides" "$logFileHint"
fi

if [ $simtime -eq 1 ]; then
  tmux_cmd_canton canton-simtime canton-simtime.tokens canton-simtime.participants \
     ./apps/app/src/test/resources/simple-topology-canton-simtime.conf \
     "$config_overrides_simtime" canton-simtime
fi

if [[ $collect_metrics -eq 1 ]]; then
  ./scripts/start-opentelemetry-collector.sh
fi

if [ $daemon -ne 3 ]; then
  if [ $wallclocktime -eq 0 ]; then
    ./wait-for-canton.sh -s
  elif [ $simtime -eq 0 ]; then
    ./wait-for-canton.sh -w
  else
    ./wait-for-canton.sh
  fi
else
  echo "-D specified, not waiting for canton to start "
fi

tmux_cmd toxiproxy "toxiproxy-server 2>&1 | tee -a log/toxi.log"

if [ $daemon -eq 0 ]; then
  if [ -z "${TMUX-}" ]; then
    tmux attach -t "${tmux_session}"
  else
    echo "Running inside tmux. To attach to canton terminal, type the following from a new terminal:"
    echo "  tmux attach -t ${tmux_session}"
  fi
elif [ $daemon -eq 2 ]; then
  echo ""
  echo ""
  echo "-b specified, sleeping forever keeping the process alive."
  sleep infinity
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to canton terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
