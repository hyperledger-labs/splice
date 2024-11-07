#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

mkdir -p /tmp/classes
# We cache */target/scala/* files and */target/streams/* which
# includes everything that is required for SBT to do fast rebuilds
# while avoiding issues arising from caching typescript, daml files or anything else.
find . -type f -a \( -path '*/target/scala*' -o -path '*/target/streams/*' \) | tar --use-compress-program=pigz -cf /tmp/classes/classes.tar.gz --exclude-vcs -T -
