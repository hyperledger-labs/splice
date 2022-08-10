#!/usr/bin/env bash
set -eou pipefail
rm -f canton.ports
SDK_VERSION=2.4.0-snapshot.20220801.10312.0.d2c7be9d
CANTON_VERSION=20220802
if [ ! -d canton-release ]; then
    echo "No canton release in canton-release, downloading"
    mkdir canton-release
    curl -sSL https://github.com/digital-asset/daml/releases/download/v2.4.0-snapshot.20220801.10312.0.d2c7be9d/canton-open-source-20220802.tar.gz | tar xz --strip-components=1 -C canton-release
fi
./canton-release/bin/canton \
    daemon --auto-connect-local --log-level-canton=DEBUG \
    --no-tty -c ./apps/app/src/test/resources/simple-topology-canton.conf -C canton.parameters.ports-file=canton.ports &
PID=$!
echo "$PID" > canton.pid
while [ ! -f canton.ports ]; do
    echo "Waiting for Canton to start"
    sleep 1;
done
echo "Canton started"
