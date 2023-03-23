#!/usr/bin/env bash

set -eou pipefail

EXE=$(readlink -f cn-image-bin)

declare -a ARGS=( daemon --no-tty --log-encoder=json --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off )

if [ -f /app/pre-bootstrap.sh ]; then
  echo "Running /app/pre-bootstrap.sh"
  /app/pre-bootstrap.sh
fi

if [ -f /app/bootstrap.sc ]; then
  ARGS+=( --bootstrap /app/bootstrap-entrypoint.sc )
fi

if [ -f /app/app.conf ]; then
   ARGS+=( --config /app/app.conf )
fi

if [ -n "${ADDITIONAL_CONFIG:-}" ]; then
   echo "${ADDITIONAL_CONFIG}" > /app/additional-config.conf
   ARGS+=( --config /app/additional-config.conf )
fi

echo "Starting '${EXE}' with arguments: ${ARGS[*]}"

exec "$EXE" "${ARGS[@]}"
