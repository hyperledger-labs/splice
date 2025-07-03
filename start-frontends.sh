#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

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
  tmux send-keys -t "$t" "cd $wd" C-m
  tmux send-keys -t "$t" "$cmd" C-m
  tmux_window=$((tmux_window + 1))
}

function start_frontend() {
  local app=$1
  local port=$2
  local user=$3
  local node_name=$4
  local test_auth=$5

  local frontend_dir="${SPLICE_ROOT}/apps/${app}/frontend"

  # Note: We are sending the content of the whole config.js file as a string to the webpack dev server.
  # There are two issues with this:
  # - The config contains quotes and line breaks
  # - The command 'tmux send-keys' does not handle sending long strings well
  # To avoid both issues, we are saving the content of the config to a temporary file
  # and reading it back from the tmux session.
  local config_file
  config_file=$(mktemp)

  local splice_instance_names="{
  spliceInstanceNames: {
    networkName: 'Splice',
    networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
    amuletName: 'Amulet',
    amuletNameAcronym: 'AMT',
    nameServiceName: 'Amulet Name Service',
    nameServiceNameAcronym: 'ANS',
  }
}"

  # Until this is open-sourced, we don't need to extract further
  local auth0Config="{
  authority: 'https://${SPLICE_OAUTH_TEST_AUTHORITY}',
  clientId: '$SPLICE_OAUTH_TEST_FRONTEND_CLIENT_ID',
  audience: '${OIDC_AUTHORITY_LEDGER_API_AUDIENCE}',
  }"

  jsonnet \
    --tla-str clusterProtocol="http" \
    --tla-str clusterAddress="localhost" \
    --tla-str authAlgorithm="rs-256" \
    --tla-str enableTestAuth="$test_auth" \
    --tla-code auth0Config="$auth0Config" \
    --tla-str validatorNode="$node_name" \
    --tla-str app="$app" \
    --tla-str port="$port" \
    --tla-code spliceInstanceNames="$splice_instance_names" \
    "$SPLICE_ROOT/apps/app/src/test/resources/frontend-config.jsonnet" \
    >"$config_file"

  # This is the URL the frontend talks to which is then rewritten using th vite proxy to the actual url of the backend
  JSON_API_URL=$(jq -r '.services.jsonApiBackend.url' <"$config_file")

  local log_file="${LOG_DIR}/npm-${app}-${user}.out"

  tmux_cmd "${app}-${user}" "${frontend_dir}" \
    "trap \"rm -f ${config_file}\" EXIT"

  tmux send-keys -t "${tmux_session}:$((tmux_window - 1))" \
    "BROWSER=none PORT=$port JSON_API_URL=$JSON_API_URL VITE_SPLICE_CONFIG=\"\$(cat $config_file)\" \
    npm start 2>&1 | tee -a $log_file" C-m
}

function start_test() {
  local app=$1
  local frontend_dir="${SPLICE_ROOT}/apps/${app}/frontend"

  tmux_cmd "${app}-test" "${frontend_dir}" "npm run test"
}

function usage() {
  echo "Usage: ./start-frontends.sh <flags>"
  echo "Flags:"
  echo "  -h        display this help message"
  echo "  -d        start in detached mode"
  echo "  -a        run all frontends with canton-network-test auth0 tenant and no test auth"
  echo "  -v        run frontends with a shared validator for all users"
  echo "  -s        run frontends with two super validators for Sv*IntegrationTest in CI"
  echo "  -t        start interactive/live vitest suites for frontends"
}

# default values
daemon=0
enable_test_auth="true"
shared_validator_for_users=0
two_svs=0
run_tests=0

while getopts "hdapvsmtl" arg; do
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
    v)
      shared_validator_for_users=1
      ;;
    s)
      two_svs=1
      ;;
    t)
      run_tests=1
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

tmux_session="cn-frontends"
tmux_window=0

LOG_DIR="${SPLICE_ROOT}/log"

(cd "$SPLICE_ROOT" && sbt --batch apps-frontends/compile)

tmux new-session -d -s "${tmux_session}"
mkdir -p "${LOG_DIR}"

function wait_for_workspace_build() {
  local workspace=$1
  local index=$2 # relative to apps/
  # workspace typically includes a namespace and /, which will make tee fail;
  # remove it if present
  local log_file_suffix="${workspace##*/}"
  local log_file="${LOG_DIR}/npm-${log_file_suffix}.out"
  echo "Logging to ${log_file}"

  tmux_cmd "$workspace" "$SPLICE_ROOT/apps" "npm run start --workspace $workspace 2>&1 | tee -a $log_file"

  local count=0
  while [ ! -f "$SPLICE_ROOT/apps/$index" ]; do
    echo "Waiting for $workspace to start..."
    sleep 1
    count=$((++count))
    if [ "$count" -ge "100" ]; then
      echo "Failure to start $workspace, exiting"
      exit 1
    fi
  done
}

# listen & auto-rebuild all these frontend workspaces' code when its src changes
wait_for_workspace_build "@lfdecentralizedtrust/splice-common-frontend-utils" "common/frontend/utils/lib/index.js"
wait_for_workspace_build "@lfdecentralizedtrust/splice-common-test-vite-utils" "common/frontend-test-vite-utils/lib/cjs/package.json"
wait_for_workspace_build "@lfdecentralizedtrust/splice-common-test-utils" "common/frontend-test-utils/lib/index.js"
wait_for_workspace_build "@lfdecentralizedtrust/splice-common-test-handlers" "common/frontend-test-handlers/lib/index.js"
# order matters, common-frontend depends on all the above
wait_for_workspace_build "@lfdecentralizedtrust/splice-common-frontend" "common/frontend/lib/index.js"

# The set of frontends we want to start as part of typical integration testing
function start_local_frontends() {
  validator_for_bob="bob"
  if [ $shared_validator_for_users -eq 1 ]; then
    validator_for_bob="alice"
  fi

  # start_frontend <app>     <ui-http-port> <user-name> <validator-name> <enable-test-auth> <algorithm> <cluster-address>

  # Wallet
  start_frontend wallet 3000 alice "alice" $enable_test_auth
  start_frontend wallet 3001 bob $validator_for_bob $enable_test_auth
  start_frontend wallet 3011 sv1 "sv1" $enable_test_auth

  # ANS
  start_frontend ans 3100 alice "alice" $enable_test_auth

  # SV
  start_frontend sv 3211 sv1 "sv1" $enable_test_auth

  if [ $two_svs -eq 1 ]; then
    start_frontend sv 3212 sv2 "sv2" $enable_test_auth
  fi

  # Scan
  start_frontend scan 3311 scan "scan" "false"

  # Splitwell
  start_frontend splitwell 3400 alice "alice" $enable_test_auth
  start_frontend splitwell 3401 bob $validator_for_bob $enable_test_auth
  start_frontend splitwell 3402 charlie "alice" $enable_test_auth
}

# The set of tests we want to start for local unit testing
function start_local_tests() {
  start_test ans
  start_test scan
  start_test splitwell
  start_test sv
  start_test wallet
  start_test common
}

if [ $run_tests -eq 1 ]; then
  start_local_tests
else
  start_local_frontends
fi

if [ $daemon -eq 0 ]; then
  if [ -z "${TMUX-}" ]; then
    tmux attach -t "${tmux_session}"
  else
    echo "Running inside tmux. To attach to frontends terminal, type the following from a new terminal:"
    echo "  tmux attach -t ${tmux_session}"
  fi
else
  echo ""
  echo ""
  echo "-d specified, running in daemon mode. To attach to frontends terminal, type:"
  echo "  tmux attach -t ${tmux_session}"
fi
