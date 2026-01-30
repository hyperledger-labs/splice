#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -x

if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/copy-canton.sh path/to/canton-oss"
    exit 1
fi

rsync -av --delete --exclude version.sbt --exclude community-build.sbt --exclude deployment --exclude project --exclude scripts --exclude .idea \
    --exclude=.github --exclude=.git --exclude=.gitmodules --exclude 'LICENSE*.txt' --exclude README.md --exclude demo --exclude '*/test/daml' \
    --exclude /daml --exclude daml-common-staging --exclude '*/ledger-common-dars' --exclude '*/daml/CantonExamples' \
    --exclude '*/wartremove/test/*' --exclude "*/ledger-api-bench-tool" \
    --exclude '*/canton-community-app/test/scala/*/integration/tests' "$1/" canton/
# remove any broken symlinks after the copy
find -L canton/ -type l -exec rm {} +
