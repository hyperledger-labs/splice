#!/usr/bin/env bash
set -eou pipefail

PID=$(cat canton.pid)
kill $PID
rm canton.pid
