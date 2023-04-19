#!/usr/bin/env bash
set -eou pipefail

tmux_session="cn-frontends"
tmux kill-session -t "${tmux_session}" || true

if [ -f start-frontends-network-address ]; then
    rm start-frontends-network-address
fi
