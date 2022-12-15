#!/usr/bin/env bash

set -x

if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/copy-canton.sh path/to/canton-oss"
    exit 1
fi

rsync -av --delete --exclude version.sbt --exclude community-build.sbt --exclude deployment --exclude project --exclude scripts --exclude=.github --exclude=.git --exclude LICENSE.txt --exclude README.md --exclude demo --exclude daml $1/ canton/
