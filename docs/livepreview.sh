#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ $# -eq 1 ]] && [[ "$1" == "--skip-daml" ]]; then
  echo "Skipping generation of DAML docs as requested."
else
  # Generate DAML docs
  (cd "$SPLICE_ROOT"; sbt --batch damlBuild)
  ./gen-daml-docs.sh
fi

# Use ISO 8601 date format, which does not contain spaces, to avoid not recognizing wrong spaces in the docs preview
VERSION="live-preview-build-$(date -u +%Y-%m-%dT%H:%M:%SZ)" sphinx-autobuild src html/html -D todo_include_todos=1
