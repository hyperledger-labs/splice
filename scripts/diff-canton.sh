#!/usr/bin/env bash


if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/diff-canton.sh path/to/canton-oss"
    exit 1
fi

diff -ur -x VERSION -x version.sbt -x community-build.sbt -x deployment -x project -x scripts $1 canton
