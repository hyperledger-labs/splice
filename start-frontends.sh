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
  local user=$3
  local node_name=$4
  local test_auth=$5

  local frontend_dir="${REPO_ROOT}/apps/${app}/frontend"

  # Note: We are sending the content of the whole config.js file as a string to the webpack dev server.
  # There are two issues with this:
  # - The config contains quotes and line breaks
  # - The command 'tmux send-keys' does not handle sending long strings well
  # To avoid both issues, we are saving the content of the config to a temporary file
  # and reading it back from the tmux session.
  local config_file=$(mktemp)

  jsonnet \
    --tla-str enableTestAuth="$test_auth" \
    --tla-str validatorNode="$node_name" \
    --tla-str app="$app" \
    $REPO_ROOT/apps/app/src/test/resources/frontend-config.jsonnet \
    > "$config_file"

  local log_file="${LOG_DIR}/npm-${app}-${user}.log"

  tmux_cmd "${app}-${user}" "${frontend_dir}" \
    "trap \"rm -f ${config_file}\" EXIT"

  tmux send-keys -t "${tmux_session}:$((tmux_window-1))" \
    "BROWSER=none PORT=$port REACT_APP_CANTON_NETWORK_CONFIG=\"\$(cat $config_file)\" \
    npm start 2>&1 | tee -a $log_file" C-m
}

function usage() {
  echo "Usage: ./start-frontends.sh <flags>"
  echo "Flags:"
  echo "  -h   display this help message"
  echo "  -d   start in detached mode"
  echo "  -a   run all frontends with canton-network-test auth0 tenant and no test auth"
}

# default values
daemon=0
enable_test_auth="true"

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
      enable_test_auth="false"
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

(cd $REPO_ROOT && sbt --batch apps-frontends/compile)

tmux new-session -d -s "${tmux_session}"

# listen & auto-rebuild common-frontend code when its src changes
tmux_cmd "common-frontend" "$REPO_ROOT/apps" "npm run start --workspace common-frontend 2>&1 | tee ${LOG_DIR}/npm-common.log"

while [ ! -f "$REPO_ROOT/apps/common/frontend/lib/index.js" ]
do
    echo "Waiting for common-frontend to start..."
    sleep 1
done

# start_frontend <app> <ui-http-port> <user-name> <validator-name> <enable-test-auth>
start_frontend wallet    3000 alice   "alice" $enable_test_auth
start_frontend wallet    3001 bob     "bob"   $enable_test_auth
start_frontend splitwell 3002 alice   "alice" $enable_test_auth
start_frontend splitwell 3003 bob     "bob"   $enable_test_auth
start_frontend directory 3004 alice   "alice" $enable_test_auth
start_frontend splitwell 3005 charlie "alice" $enable_test_auth

if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
