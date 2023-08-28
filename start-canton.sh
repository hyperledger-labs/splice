#!/usr/bin/env bash
set -eou pipefail

function usage() {
  echo "Usage: ./start-canton.sh <flags>"
  echo "Flags:"
  echo "  -h               display this help message"
  echo "  -d               start in detached mode"
  echo "  -b               start in the background"
  echo "  -p postgres_mode postgres mode used in scripts/postgres.sh, default 'docker'"
  echo "  -w               only start canton instance with wall clock time"
  echo "  -s               only start canton instance with simulated time"
  echo "  -f               start canton using the CometBFT driver for the global sequencers"
  echo "  -g               start extra global upgrade domain"
  echo "  -m               collect metrics and send them to our CI prometheus instance"
  echo "  -c <canton>      start a custom canton binary instead of the one on the PATH"
}

# default values
daemon=0
wallclocktime=1
simtime=1
POSTGRES_MODE=docker
CANTON=canton
globalUpgradeDomain=0
bootstrapScriptPath=bootstrap-canton.sc
global_cometbft=0
collect_metrics=0

while getopts "hdap:c:wsbtfgm" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    b)
      daemon=2
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
    f)
      global_cometbft=1
      echo "start canton with the cometbft driver"
      ;;
    g)
      globalUpgradeDomain=1
      echo "start extra global upgrade domain"
      ;;
    m)
      collect_metrics=1
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [ $globalUpgradeDomain -ne 0 ] && [ $wallclocktime -ne 0 ]; then
  >&2 echo "-g requires -s to be passed as well"
  exit 1
fi

tmux_session="canton"
tmux_window=0

if tmux has-session -t $tmux_session 2>/dev/null; then
  >&2 echo "Canton seems to already be running. Did you mean to run stop-canton.sh first?"
  exit 1
fi

rm -f canton*.tokens

# Start Postgres
./scripts/postgres.sh "$POSTGRES_MODE" start

# Start CometBFT
if [[ $global_cometbft -eq 1 ]]; then
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
)

db_names=()
if [ $wallclocktime -eq 1 ]; then
  db_names+=(
    "${any_time_db_names[@]}"
    "self_hosted_participant"
  )
fi

if [ $simtime -eq 1 ]; then
  # same names, but with _simtime suffix
  IFS=' ' read -r -a simtime_db_names <<< \
      "$(echo "${any_time_db_names[@]}" | sed -Ee 's/( |$)/_simtime\1/g')"
  db_names+=("${simtime_db_names[@]}")

  if [ $globalUpgradeDomain -eq 1 ]; then
    db_names+=(
      "sequencer_driver_global_upgrade_simtime"
      "sequencer_global_upgrade_simtime"
      "mediator_global_upgrade_simtime"
    )
  fi
fi

# Create the DB's in parallel
printf '%s\n' "${db_names[@]}" | xargs -P 64 -I {} ./scripts/postgres.sh "$POSTGRES_MODE" createdb {}

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
JAVA_TOOL_OPTIONS="-Xms6g -Xmx6g -Dlogback.configurationFile=./scripts/canton-logback.xml"

config_overrides=""
config_overrides_simtime=""

# Enable traffic QoS
config_overrides="$config_overrides -c ./apps/app/src/test/resources/domain-fees-overrides.conf"

if [[ $global_cometbft -eq 1 ]]; then
  config_overrides="$config_overrides -c ./apps/app/src/test/resources/cometbft-sequencer-global-domain-overrides.conf"
fi;

if [ $globalUpgradeDomain -eq 1 ]; then
  combinedBootstrapScriptPath="$(mktemp --suffix=.sc)"
  sed -e '/Inserting extra commands here (do not edit this line)/r bootstrap-canton-global-upgrade.sc' \
      "$bootstrapScriptPath" > "$combinedBootstrapScriptPath"
  bootstrapScriptPath="$combinedBootstrapScriptPath"
  config_overrides_simtime="$config_overrides_simtime -c ./apps/app/src/test/resources/global-upgrade-domain-simtime-overrides.conf"
fi

tmux_cmd_canton() {
  windowName="$1" tokensFile="$2" baseConfig="$3" confOverrides="$4" logFile="$5"
  tmux_cmd "$windowName" \
    "EXTRA_CLASSPATH=$COMETBFT_DRIVER/driver.jar \
     COMETBFT_DOCKER_IP=${COMETBFT_DOCKER_IP-} \
     CANTON_TOKEN_FILENAME=$tokensFile JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS\" $CANTON \
      -c $baseConfig $confOverrides \
      --log-level-canton=DEBUG \
      --log-encoder json \
      --log-file-name $logFile \
      --bootstrap $bootstrapScriptPath"
}

if [ $wallclocktime -eq 1 ]; then
  tmux_cmd_canton canton canton.tokens \
    ./apps/app/src/test/resources/simple-topology-canton.conf \
    "$config_overrides" log/canton.clog
fi

if [ $simtime -eq 1 ]; then
  tmux_cmd_canton canton-simtime canton-simtime.tokens \
     ./apps/app/src/test/resources/simple-topology-canton-simtime.conf \
     "$config_overrides $config_overrides_simtime" log/canton-simtime.clog
fi

if [[ $collect_metrics -eq 1 ]]; then
  ./scripts/start-opentelemetry-collector.sh
fi

if [ $wallclocktime -eq 0 ]; then
  ./wait-for-canton.sh -s
elif [ $simtime -eq 0 ]; then
  ./wait-for-canton.sh -w
else
  ./wait-for-canton.sh
fi


tmux_cmd toxiproxy toxiproxy-server > log/toxi.log 2>&1

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
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
