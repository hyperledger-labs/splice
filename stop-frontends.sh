#!/usr/bin/env bash
set -eou pipefail

tmux_session="cn-frontends"
tmux kill-session -t "${tmux_session}" || true

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "${script_dir}/envoy-proxy-dev"
./stop-envoy.sh
cd -
