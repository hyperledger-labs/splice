#!/usr/bin/env bash
set -eou pipefail

tmux_session="cn-frontends"
if tmux has-session -t $tmux_session 2>/dev/null; then
    # kill-session seems to send a SIGHUP which does not seem to be quite enough
    # to tear down the processes promptly so we manually kill them.
    # parents will usually be some shell process.
    readarray -t TMUX_PARENT_PIDS < <(tmux list-panes -s -F "#{pane_pid}" -t $tmux_session)
    # the child is sometimes the actual process we want to kill
    readarray -t TMUX_CHILD_PIDS < <(echo -n "${TMUX_PARENT_PIDS[@]}" | xargs -d ' ' -I '{}' pgrep -P '{}' || true)
    # but sometimes (e.g., for envoy) the child is only another shell process and the *grandchild* is the process we want to kill
    readarray -t TMUX_GRAND_CHILD_PIDS < <(echo -n "${TMUX_CHILD_PIDS[@]}" | xargs -d ' ' -I '{}' pgrep -P '{}' || true)
    kill -9 "${TMUX_GRAND_CHILD_PIDS[@]}" "${TMUX_CHILD_PIDS[@]}" "${TMUX_PARENT_PIDS[@]}"
    # Session might be dead at this point because we killed all processes
    # but to be on the safe side we still kill it.
    tmux kill-session -t $tmux_session 2>/dev/null || true
fi

if [ -f start-frontends-network-address ]; then
    rm start-frontends-network-address
fi
