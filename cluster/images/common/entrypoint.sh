#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

source /app/tools.sh

EXE=$(readlink -f splice-image-bin)

declare -a ARGS=()

# support starting the image as a remote console
if [[ ! " ${*} " =~ " --console " ]]; then
    ARGS+=( daemon --no-tty )
fi

ARGS+=( --log-encoder=json --log-level-stdout="${LOG_LEVEL_STDOUT:-DEBUG}" --log-level-canton="${LOG_LEVEL_CANTON:-DEBUG}" --log-file-appender=off )

if [ -f /app/logback.xml ]; then
   export JAVA_TOOL_OPTIONS="-Dlogback.configurationFile=/app/logback.xml ${JAVA_TOOL_OPTIONS:-}"
fi

if [ -f /app/pre-bootstrap.sh ]; then
  json_log "Running /app/pre-bootstrap.sh" "entrypoint.sh"
  source /app/pre-bootstrap.sh
fi

if [ -n "${OVERRIDE_BOOTSTRAP_SCRIPT:-}" ]; then
  json_log "Overwriting bootstrap script from environment variable"
  echo "$OVERRIDE_BOOTSTRAP_SCRIPT" > /app/bootstrap.sc
fi

if [ -f /app/bootstrap.sc ]; then
  ARGS+=( --bootstrap /app/bootstrap-entrypoint.sc )
fi

if [ -f /app/app.conf ]; then
   ARGS+=( --config /app/app.conf )
fi

# Concatenate all additional configurations passed through env variables of the form ADDITIONAL_CONFIG*
for cfg in ${!ADDITIONAL_CONFIG@}; do
   echo "${!cfg}"
done >> /app/additional-config.conf

if [ -s "/app/monitoring.conf" ]; then
   ARGS+=( --config /app/monitoring.conf )
fi

if [ -s "/app/parameters.conf" ]; then
   ARGS+=( --config /app/parameters.conf )
fi

if [ -s "/app/additional-config.conf" ]; then
   ARGS+=( --config /app/additional-config.conf )
fi

# The default maximum for malloc arenas is 8 * num_of_cpu_cores with no respect
# to container limits.  JVM has it's own memory management, so high number of
# arenas doesn't provide any significant pefromance improvement, however it
# does increase the memory footprint of a long running process.  We limit the
# number of arenas to 2 (main and one additional arena) by setting environment
# variable MALOC_ARENA_MAX.
#
# Setting SPLICE_MALLOC_ARENA_MAX to 0 or '' will disable the limit and use the
# default value.
export MALLOC_ARENA_MAX=${SPLICE_MALLOC_ARENA_MAX-2}

json_log "Starting '${EXE}' with arguments: ${ARGS[*]}" "entrypoint.sh"

exec "$EXE" "${ARGS[@]}"
