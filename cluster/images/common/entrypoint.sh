#!/usr/bin/env bash

set -eou pipefail

EXE=$(readlink -f cn-image-bin)

ARGS="daemon --no-tty --log-encoder=json --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off"

if [ -f /app/pre-bootstrap.sh ]; then
  echo "Running /app/pre-bootstrap.sh"
  /app/pre-bootstrap.sh
fi

if [ -f /app/bootstrap.sc ]; then
  ARGS="${ARGS} --bootstrap /app/bootstrap-entrypoint.sc"
fi

if [ -f /app/app.conf ]; then
   ARGS="${ARGS} --config /app/app.conf"
fi

if [ ! -z "${ADDITIONAL_CONFIG:-}" ]; then
   echo "${ADDITIONAL_CONFIG}" > /app/additional-config.conf
   ARGS="${ARGS} --config /app/additional-config.conf"
fi

echo "Starting '${EXE}' with arguments: ${ARGS}"

exec $EXE $ARGS
