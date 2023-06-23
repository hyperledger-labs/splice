#!/usr/bin/env bash

set -eou pipefail

source /app/tools.sh

EXE=$(readlink -f cn-image-bin)

declare -a ARGS=( daemon --no-tty --log-encoder=json --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off )

if [ -f /app/logback.xml ]; then
   export JAVA_TOOL_OPTIONS="-Dlogback.configurationFile=/app/logback.xml ${JAVA_TOOL_OPTIONS:-}"
fi

if [ -f /app/pre-bootstrap.sh ]; then
  json_log "Running /app/pre-bootstrap.sh" "entrypoint.sh"
  source /app/pre-bootstrap.sh
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

if [ -s "/app/additional-config.conf" ]; then
   ARGS+=( --config /app/additional-config.conf )
fi

json_log "Starting '${EXE}' with arguments: ${ARGS[*]}" "entrypoint.sh"

exec "$EXE" "${ARGS[@]}"
