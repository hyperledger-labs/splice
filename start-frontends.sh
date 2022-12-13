#!/usr/bin/env bash
set -eou pipefail

function start_envoy() {
  cd "${REPO_ROOT}/envoy-proxy-dev"
  ./start-envoy.sh
  cd -
}

function check_envoy_running() {
  ENVOY_PID=`cat ${REPO_ROOT}/envoy-proxy-dev/envoy.pid`
  if [[ -z "$(ps -p $ENVOY_PID -o pid=)" ]]; then
    echo "envoy failed to start" >&2
    return 1
  fi
}

function tmux_cmd() {
  local title=$1
  local wd=$2
  local cmd=$3
  local t=${tmux_session}:${tmux_window}

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
  local app=$1
  local port=$2
  local app_grpc=$3
  local wallet_port=$4
  local ledger_grpc=$5
  local validator_grpc=$6
  local scan_grpc=$7
  local user=$8
  local oa_domain=$9
  local oa_clientid="${10}"
  local algorithm="${11}"
  local test_auth_secret="${12}"

  local frontend_dir="${REPO_ROOT}/apps/${app}/frontend"

  tmux_cmd "${app}-${user}" "${frontend_dir}" \
    "BROWSER=none PORT=$port \
    REACT_APP_SERVICE_WALLET_GRPC_URL=http://localhost:${app_grpc} \
    REACT_APP_SERVICE_WALLET_UI_URL=http://localhost:${wallet_port} \
    REACT_APP_SERVICE_VALIDATOR_GRPC_URL=http://localhost:${validator_grpc} \
    REACT_APP_SERVICE_SCAN_API_GRPC_URL=http://localhost:${scan_grpc} \
    REACT_APP_SERVICE_LEDGER_API_GRPC_URL=http://localhost:${ledger_grpc} \
    REACT_APP_AUTH_AUTHORITY=${oa_domain} \
    REACT_APP_AUTH_CLIENT_ID=${oa_clientid} \
    REACT_APP_AUTH_ALGORITHM=${algorithm} \
    REACT_APP_AUTH_TOKEN_AUDIENCE="https://canton.network.global" \
    REACT_APP_AUTH_TOKEN_SCOPE="daml_ledger_api" \
    REACT_APP_TESTAUTH_TOKEN_AUDIENCE="https://canton.network.global" \
    REACT_APP_TESTAUTH_TOKEN_SCOPE="daml_ledger_api" \
    REACT_APP_TESTAUTH_SECRET=${test_auth_secret} \
    npm start 2>&1 | tee ${LOG_DIR}/npm-${app}-${user}.log"
}

function usage() {
  echo "Usage: ./start-frontends.sh <flags>"
  echo "Flags:"
  echo "  -h   display this help message"
  echo "  -d   start in detached mode"
  echo "  -a   run all frontends with canton-network-test auth0 tenant and no test auth"
}

daemon=0
# default: auth via auth0
oauth_authority=https://canton-network-test.us.auth0.com
oauth_clientid=Ob8YZSBvbZR3vsM2vGKllg3KRlRgLQSw
auth_algorithm=rs-256
# default: enable testing auth with secret "test"
test_auth_secret=test
while getopts "hda" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    d)
      daemon=1
      ;;
    a)
      test_auth_secret=
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
# envoy kills itself if we spend too much time in `start-envoy.sh`, hence we check this here...
sleep 0.5s && check_envoy_running

(cd $REPO_ROOT && sbt apps-frontends/compile)

tmux new-session -d -s "${tmux_session}"

# listen & auto-rebuild common-frontend code when its src changes
tmux_cmd "common-frontend" "$REPO_ROOT/apps" "npm run start --workspace common-frontend 2>&1 | tee ${LOG_DIR}/npm-common.log"

while [ ! -f "$REPO_ROOT/apps/common/frontend/lib/index.js" ]
do
    echo "Waiting for common-frontend to start..."
    sleep 1
done

# start_frontend <app> <ui-http-port> <app-grpc-port> <app-wallet-ui-port> <ledgerapi-grpc-port> <validator-app-grpc-port> <app-scan-grpc-port> <user-display-name>
start_frontend wallet    3000 6204 0    0    6203 6012 alice   "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"
start_frontend wallet    3001 6304 0    0    6303 6012 bob     "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"
start_frontend splitwise 3002 6113 3000 6201 0    0    alice   "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"
start_frontend splitwise 3003 6113 3001 6301 0    0    bob     "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"
start_frontend directory 3004 6110 3000 6201 0    0    alice   "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"
start_frontend splitwise 3005 6113 0    6201 0    0    charlie "$oauth_authority" "$oauth_clientid" "$auth_algorithm" "$test_auth_secret"

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
