#!/usr/bin/env bash
set -eou pipefail

function start_envoy() {
  cd "${REPO_ROOT}/envoy-proxy-dev"
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
  wallet_port=$4
  ledger_grpc=$5
  validator_grpc=$6
  user=$7

  frontend_dir="${REPO_ROOT}/apps/${app}/frontend"

  tmux_cmd "${app}-${user}" "${frontend_dir}" \
    "BROWSER=none PORT=$port \
    REACT_APP_GRPC_URL=http://localhost:${app_grpc} \
    REACT_APP_WALLET_UI_URL=http://localhost:${wallet_port} \
    REACT_APP_VALIDATOR_API_GRPC_URL=http://localhost:${validator_grpc} \
    REACT_APP_LEDGER_API_GRPC_URL=http://localhost:${ledger_grpc} \
    REACT_APP_OAUTH_DOMAIN=canton-network-test.us.auth0.com \
    REACT_APP_OAUTH_CLIENT_ID=Ob8YZSBvbZR3vsM2vGKllg3KRlRgLQSw \
    npm start 2>&1 | tee ${LOG_DIR}/npm-${app}-${user}.log"
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

LOG_DIR="${REPO_ROOT}/log"
start_envoy

(cd $REPO_ROOT && sbt apps-frontends/compile)

tmux new-session -d -s "${tmux_session}"

# listen & auto-rebuild common-frontend code when its src changes
tmux_cmd "common-frontend" "$REPO_ROOT/apps" "npm run start --workspace common-frontend 2>&1 | tee ${LOG_DIR}/npm-common.log"

while [ ! -f "$REPO_ROOT/apps/common/frontend/lib/index.js" ]
do
    echo "Waiting for common-frontend to start..."
    sleep 1
done

# start_frontend <app> <ui-http-port> <app-wallet-ui-port> <app-grpc-port> <ledgerapi-grpc-port> <validator-app-grpc-port> <user-display-name>
start_frontend wallet    3000 6204 NA   NA   6203 alice
start_frontend wallet    3001 6304 NA   NA   6303 bob
start_frontend splitwise 3002 6113 3000 6201 NA   alice
start_frontend splitwise 3003 6113 3001 6301 NA   bob
start_frontend directory 3004 6110 NA   6201 NA   alice
start_frontend splitwise 3005 6113 NA   6201 NA   charlie

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
