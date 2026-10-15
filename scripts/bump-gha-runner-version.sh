#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

runner_version=$(
  gh release view \
    --repo actions/runner \
    --json tagName \
  | jq -r '.tagName' \
  | sed 's/^v//' # remove the 'v' prefix
)

# gets the multiplatform image digest
runner_digest=$(
  docker buildx imagetools inspect "ghcr.io/actions/actions-runner:${runner_version}" \
  | yq '.Digest'
)

echo "The newest available image is ghcr.io/actions/actions-runner:${runner_version}@${runner_digest}"

sources_file="${SPLICE_ROOT}/nix/gha-runner-sources.json"

jq --indent 4 \
  --arg version "${runner_version}" \
  --arg digest "${runner_digest}" \
  '.version = $version | .digest = $digest' \
  "${sources_file}" > "${sources_file}.tmp"
mv "${sources_file}.tmp" "${sources_file}"

if git diff --exit-code --quiet "${sources_file}"; then
  echo "GHA runner version is up to date."
  exit 0
fi

echo "GHA runner version is not up to date. Creating a PR..."

git add "${sources_file}"
updated_branch="gha-runner-version-bump-$(date +%Y-%m-%d)"
git switch -c "${updated_branch}"
git commit -m "[ci] bump GHA runner version to the latest (auto-generated)" -s
git push origin "${updated_branch}"

gh pr create \
  --base "main" \
  --head "$updated_branch" \
  --title "Bump GHA runner version to the latest (auto-generated)" \
  --body "" \
  --reviewer isegall-da,martinflorian-da,ray-roestenburg-da,mblaze-da

echo "Done."
