#!/usr/bin/env bash
set -eou pipefail

POSTGRES_MODE=${1:-docker}

tmux_session="canton"
if tmux has-session -t $tmux_session 2>/dev/null; then
    # kill-session seems to send a SIGHUP which does not seem to be quite enough
    # to tear down the processes promptly so we manually kill them.
    # parents will usually be some shell process.
    readarray -t TMUX_PARENT_PIDS < <(tmux list-panes -s -F "#{pane_pid}" -t $tmux_session)
    # the child is the actual process we want to kill
    readarray -t TMUX_CHILD_PIDS < <(echo -n "${TMUX_PARENT_PIDS[@]}" | xargs -d ' ' -I '{}' pgrep -P '{}' || true)
    kill -9 "${TMUX_CHILD_PIDS[@]}" "${TMUX_PARENT_PIDS[@]}"
    # Session might be dead at this point because we killed all processes
    # but to be on the safe side we still kill it.
    tmux kill-session -t $tmux_session 2>/dev/null || true
fi


rm -f canton.tokens canton-simtime.tokens

./scripts/postgres.sh "$POSTGRES_MODE" stop
