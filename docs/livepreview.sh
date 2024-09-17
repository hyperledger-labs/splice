#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

(cd "$REPO_ROOT"; sbt --batch damlBuild)
./gen-daml-docs.sh

VERSION="live-preview-build-$(date)" sphinx-autobuild src html/html
