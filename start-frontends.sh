#!/usr/bin/env bash
set -eou pipefail

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
  tmux send-keys -t "$t" "nix develop path:nix" C-m
  tmux send-keys -t "$t" "cd $wd" C-m
  tmux send-keys -t "$t" "$cmd" C-m
  tmux_window=$((tmux_window+1))
}

function start_envoy() {
  jsonnet --tla-str hostname="127.0.0.1" "${REPO_ROOT}/envoy-proxy-dev/envoy.jsonnet" > "${REPO_ROOT}/envoy-proxy-dev/envoy-config.json"
  if [ -z "$(which envoy)" ]; then
    echo "envoy executable not found. On MacOS, please install envoy globally using brew" >&2
    exit 1
  fi
  tmux_cmd "envoy" "${REPO_ROOT}/envoy-proxy-dev" "envoy --log-level debug --log-path ${LOG_DIR}/envoy-system.log -c envoy-config.json | tee ${LOG_DIR}/envoy-out.log 2>&1"
}

function check_envoy_running() {
  # envoy refuses to come back to the foreground if originally started in the background,
  # so we can't write out its PID in the same script we start it in
  ENVOY_PID=$(pgrep envoy)
  if [[ -z "$ENVOY_PID" ]] || [[ -z "$(ps -p "$ENVOY_PID" -o pid=)" ]]; then
    return 1
  fi
}

function check_envoy_working() {
  # Call the admin API of envoy to check whether envoy is ready.
  # This port used by envoy is defined in `envoy-proxy-dev/envoy.jsonnet`.
  if [[ "$(curl -s localhost:9901/ready)" != "LIVE" ]]; then
    return 1
  fi
}

function start_frontend() {
  local app=$1
  local port=$2
  local user=$3
  local node_name=$4
  local test_auth=$5
  local algorithm="${6:-rs-256}"
  local cluster_protocol="${7:-'http'}"
  local cluster_address="${8:-'localhost'}"

  local frontend_dir="${REPO_ROOT}/apps/${app}/frontend"

  # Note: We are sending the content of the whole config.js file as a string to the webpack dev server.
  # There are two issues with this:
  # - The config contains quotes and line breaks
  # - The command 'tmux send-keys' does not handle sending long strings well
  # To avoid both issues, we are saving the content of the config to a temporary file
  # and reading it back from the tmux session.
  local config_file
  config_file=$(mktemp)

  jsonnet \
    --tla-str clusterProtocol="$cluster_protocol" \
    --tla-str clusterAddress="$cluster_address" \
    --tla-str authAlgorithm="$algorithm" \
    --tla-str enableTestAuth="$test_auth" \
    --tla-str validatorNode="$node_name" \
    --tla-str app="$app" \
    --tla-str port="$port" \
    "$REPO_ROOT/apps/app/src/test/resources/frontend-config.jsonnet" \
    > "$config_file"

  # This is the URL the frontend talks to which is then rewritten using setupProxy.js
  # to the actual URL of the backend.
  JSON_API_URL=$(jq -r '.services.jsonApiBackend.url' < "$config_file")

  local log_file="${LOG_DIR}/npm-${app}-${user}.log"

  tmux_cmd "${app}-${user}" "${frontend_dir}" \
    "trap \"rm -f ${config_file}\" EXIT"

  tmux send-keys -t "${tmux_session}:$((tmux_window-1))" \
    "BROWSER=none PORT=$port JSON_API_URL=$JSON_API_URL REACT_APP_CANTON_NETWORK_CONFIG=\"\$(cat $config_file)\" \
    npm start 2>&1 | tee -a $log_file" C-m
}

function usage() {
  echo "Usage: ./start-frontends.sh <flags>"
  echo "Flags:"
  echo "  -h   display this help message"
  echo "  -d   start in detached mode"
  echo "  -a   run all frontends with canton-network-test auth0 tenant and no test auth"
  echo "  -p   run the frontends needed for the preflight self-hosted directory UI test"
  echo "  -e   run frontends with dedicated validator for users"
  echo "  -s   run frontends with multiple super validators for Sv*IntegrationTest in CI"
}

# default values
daemon=0
enable_test_auth="true"
use_preflight_frontends=0
dedicated_validator_for_users=0
multiple_svs=0

while getopts "hdapes" arg; do
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
    p)
      use_preflight_frontends=1
      ;;
    e)
      dedicated_validator_for_users=1
      ;;
    s)
      multiple_svs=1
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

if check_envoy_running; then
  echo "envoy is already running, exiting"
  exit 1
fi

(cd "$REPO_ROOT" && sbt --batch apps-frontends/compile)

tmux new-session -d -s "${tmux_session}"
mkdir -p "${LOG_DIR}"

start_envoy
# envoy's startup is weird, so we check this here...
while ! check_envoy_working; do
    echo "Waiting for envoy to start..."
    sleep 1s;
done

# listen & auto-rebuild common-frontend code when its src changes
tmux_cmd "common-frontend" "$REPO_ROOT/apps" "npm run start --workspace common-frontend 2>&1 | tee ${LOG_DIR}/npm-common.log"

count=0
while [ ! -f "$REPO_ROOT/apps/common/frontend/lib/index.js" ]
do
    echo "Waiting for common-frontend to start..."
    sleep 1
    count=$(( ++count ))
    if [ "$count" -ge "100" ]; then
      echo "Failure to start common-frontend, exiting"
      exit 1
    fi
done

# The set of frontends we want to start as part of typical integration testing
function start_local_frontends() {
  validator_for_bob="alice"
  if [ $dedicated_validator_for_users -eq 1 ]; then
    validator_for_bob="bob"
  fi

  # start_frontend <app>     <ui-http-port> <user-name> <validator-name> <enable-test-auth> <algorithm> <cluster-address>
  start_frontend   wallet    3000 alice   "alice"              $enable_test_auth
  start_frontend   wallet    3001 bob     $validator_for_bob   $enable_test_auth
  start_frontend   splitwell 3002 alice   "alice"              $enable_test_auth
  start_frontend   splitwell 3003 bob     $validator_for_bob   $enable_test_auth
  start_frontend   directory 3004 alice   "alice"              $enable_test_auth
  start_frontend   splitwell 3005 charlie "alice"              $enable_test_auth
  start_frontend   sv        3010 sv1     "sv1"                $enable_test_auth
  start_frontend   wallet    3011 sv1     "sv1"                $enable_test_auth
  start_frontend   scan      3006 scan    "scan"               "false"           "none"

  if [ $multiple_svs -eq 1 ]; then
    start_frontend sv 3012 sv2 "sv2" $enable_test_auth
  fi
}

# The set of frontends we want to start for the preflight self-hosted directory UI test
function start_preflight_frontends() {
  # start_frontend <app> <ui-http-port> <user-name> <validator-name> <enable-test-auth> <algorithm> <cluster-protocol> <cluster-address>
  start_frontend   wallet    3000 alice   "preflight" $enable_test_auth "rs-256" "https" "${NETWORK_APPS_ADDRESS}"
  start_frontend   directory 3004 alice   "preflight" $enable_test_auth "rs-256" "https" "${NETWORK_APPS_ADDRESS}"
}

if [ $use_preflight_frontends -eq 0 ]; then
  start_local_frontends
else
  if [ "$enable_test_auth" == "true" ]; then
    start_preflight_frontends
    echo "$NETWORK_APPS_ADDRESS" > start-frontends-network-address
  else
    echo "enable_test_auth was set to false, -p is incompatible with -a"
    exit 1
  fi
fi


if [ $daemon -eq 0 ]; then
  tmux attach -t ${tmux_session}
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
