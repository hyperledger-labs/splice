#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

VERSION=$1

TEMP_DIR=$(mktemp -d)

echo "Creating GitHub release for version ${VERSION}"

gh release create "v${VERSION}" --target "release-line-${VERSION}" --title "${VERSION}" --draft --notes ""

gsutil cp "gs://cn-release-bundles/${VERSION}_openapi.tar.gz" "${TEMP_DIR}"
gsutil cp "gs://cn-release-bundles/${VERSION}_splice-node.tar.gz" "${TEMP_DIR}"

gh release upload "v${VERSION}" "${TEMP_DIR}/${VERSION}_openapi.tar.gz"
gh release upload "v${VERSION}" "${TEMP_DIR}/${VERSION}_splice-node.tar.gz"

echo "Done creating GitHub release for version ${VERSION}, please review the draft release and publish it manually"
