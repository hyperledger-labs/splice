#!/usr/bin/env bash

EXE=$(readlink -f cn-image-bin)

ARGS="daemon --no-tty --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off"

if [ -f /app/bootstrap.scala ]; then
    ARGS="${ARGS} --bootstrap /app/bootstrap.scala"
fi

if [ -f /app/app.conf ]; then
    ARGS="${ARGS} --config /app/app.conf"
fi

echo "Starting '${EXE}' with arguments: ${ARGS}"

exec $EXE $ARGS
