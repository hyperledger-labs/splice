#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail
source util.sh

tmux_session="cn-frontends"

if tmux has-session -t=$tmux_session 2>/dev/null; then
  # kill-session seems to send a SIGHUP which does not seem to be quite enough
  # to tear down the processes promptly so we manually kill them.
  # parents will usually be some shell process.
  readarray -t TMUX_PARENT_PIDS < <(tmux list-panes -s -F "#{pane_pid}" -t $tmux_session)
  kill_process_tree "${TMUX_PARENT_PIDS[@]}"

  # Session might be dead at this point because we killed all processes
  # but to be on the safe side we still kill it.
  tmux kill-session -t $tmux_session 2>/dev/null || true
fi
