#!/usr/bin/env bash


# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/diff-canton.sh path/to/canton-oss"
    exit 1
fi

diff -ur --unidirectional-new-file -x VERSION -x version.sbt -x .git -x community-build.sbt -x deployment \
 -x project -x scripts -x .github -x .idea -x demo "$1" canton
