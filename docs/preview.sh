#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

(cd "$SPLICE_ROOT"; sbt docs/bundle)

cd html/html
python -m http.server 8000 --bind 127.0.0.1
