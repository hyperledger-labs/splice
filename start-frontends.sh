#!/usr/bin/env bash
set -eou pipefail

function build_frontend() {
  app=$1

  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  cd "${script_dir}/apps/${app}/frontend"

  if [[ -z "$(ls ../target/scala* 2>/dev/null)" ]]; then
    echo "No compilation artifacts found in app ${app}. Please compile the repo before starting frontends" 1>&2
    exit 1
  fi
  ./setup.sh
  # npm run build
  # ^^ not building production to make this script faster
  cd -
}

function start_envoy() {
  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  cd "${script_dir}/envoy-proxy-dev"
  ./start-envoy.sh
  cd -
}

function tmux_cmd() {
  title=$1
  wd=$2
  cmd=$3
  t=${tmux_session}:${tmux_window}
  if [[ ${tmux_window} -eq 0 ]]; then
    tmux rename-window -t "$t" "$title"
  else
    tmux new-window -t "$t" -n "$title"
  fi
  tmux send-keys -t "$t" "cd $wd" C-m
  tmux send-keys -t "$t" "$cmd" C-m
  tmux_window=$((tmux_window+1))
}

function start_frontend() {
  app=$1
  port=$2
  app_grpc=$3
  ledger_grpc=$4
  validator_grpc=$5
  user=$6

  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  frontend_dir="${script_dir}/apps/${app}/frontend"

  tmux_cmd "${app}-${user}" "${frontend_dir}" "BROWSER=none PORT=$port REACT_APP_GRPC_URL=http://localhost:${app_grpc} REACT_APP_VALIDATOR_API_GRPC_URL=http://localhost:${validator_grpc} REACT_APP_LEDGER_API_GRPC_URL=http://localhost:${ledger_grpc} npm start"
}

function usage() {
  echo "Usage: ./start-frontends.sh <flags>"
  echo "Flags:"
  echo "  -h   display this help message"
  echo "  -d   start in detached mode"
}

daemon=0
while getopts "hd" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    d)
      daemon=1
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

tmux_session="cn-frontends"
tmux_window=0

# TODO(i711): Move build steps into sbt
build_frontend wallet
build_frontend splitwise
build_frontend directory

start_envoy

tmux new-session -d -s "${tmux_session}"

start_frontend wallet 3000 6204 NA 6203 alice
start_frontend wallet 3001 6304 NA 6303 bob
start_frontend splitwise 3002 8082 8085 NA alice
start_frontend splitwise 3003 8082 8086 NA bob
start_frontend directory 3004 8084 8085 NA alice

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
