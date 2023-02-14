#!/usr/bin/env bash


if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/diff-canton.sh path/to/canton-oss"
    exit 1
fi

diff -urN -x VERSION -x version.sbt -x daml -x .git -x community-build.sbt -x deployment \
 -x project -x scripts -x .github -x .idea -x demo "$1" canton
