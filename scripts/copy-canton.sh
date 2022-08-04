#!/usr/bin/env bash


if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/copy-canton.sh path/to/canton-oss"
    exit 1
fi

rsync -av --delete --exclude version.sbt --exclude community-build.sbt --exclude deployment --exclude project --exclude scripts --exclude=.github --exclude LICENSE.txt --exclude README.md --exclude demo $1/ canton/
