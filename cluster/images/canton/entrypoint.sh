#!/usr/bin/env bash

ARGS="daemon --no-tty --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off"

if [ -f /canton/bootstrap.scala ]; then
    ARGS="${ARGS} --bootstrap /canton/bootstrap.scala"
fi

if [ -f /canton/canton.conf ]; then
    ARGS="${ARGS} --config /canton/canton.conf"
fi

echo "Starting 'canton' with arguments: ${ARGS}"

exec bin/canton $ARGS
