#!/usr/bin/env bash

ARGS="daemon --no-tty --log-level-stdout=DEBUG --log-level-canton=DEBUG --log-file-appender=off"

if [ -f /cn-app/bootstrap.scala ]; then
    ARGS="${ARGS} --bootstrap /cn-app/bootstrap.scala"
fi

if [ -f /cn-app/coin.conf ]; then
    ARGS="${ARGS} --config /cn-app/coin.conf"
fi

echo "Starting 'coin' with arguments: ${ARGS}"

exec coin-0.1.0-SNAPSHOT/bin/coin $ARGS
