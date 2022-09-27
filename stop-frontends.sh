#!/usr/bin/env bash
set -eou pipefail

tmux_session="cn-frontends"

tmux kill-session -t "${tmux_session}"

# TODO(i711): Use a single envoy instance for everything
script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "${script_dir}/apps/wallet/frontend"
./stop-envoy.sh
cd -

cd "${script_dir}/apps/splitwise/frontend"
./stop-envoy.sh
cd -
