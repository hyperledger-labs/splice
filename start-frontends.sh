#!/usr/bin/env bash
set -eou pipefail

tmux_session="cn-frontends"
tmux_window=0
tmux new-session -d -s "${tmux_session}"

function build_frontend() {
  app=$1

  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  cd "${script_dir}/apps/${app}/frontend"

  ./gen-ledger-api-proto.sh
  ./copy-proto-sources.sh
  ./codegen.sh
  npm install
  # npm run build
  # ^^ not building production to make this script faster
  cd -
}

function start_envoy() {
  app=$1

  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  cd "${script_dir}/apps/${app}/frontend"
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
  user=$5

  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
  frontend_dir="${script_dir}/apps/${app}/frontend"

  tmux_cmd "${app}-${user}" "${frontend_dir}" "BROWSER=none PORT=$port REACT_APP_GRPC_URL=http://localhost:${app_grpc} REACT_APP_LEDGER_API_GRPC_URL=http://localhost:${ledger_grpc} npm start"
}

# TODO(i711): Move build steps into sbt
build_frontend wallet
build_frontend splitwise

# TODO(i711): Use a single envoy instance for everything
start_envoy wallet
start_envoy splitwise

start_frontend wallet 3000 6204 NA alice
start_frontend wallet 3001 6304 NA bob
start_frontend splitwise 3002 8082 8085 alice
start_frontend splitwise 3003 8082 8086 bob

tmux attach -t ${tmux_session}
