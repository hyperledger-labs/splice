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

runner_digest=$(
  docker manifest inspect "ghcr.io/actions/actions-runner:${runner_version}" \
  | jq -r '.manifests[] | select(.platform.architecture == "amd64") | .digest'
)

docker_runner_file="${SPLICE_ROOT}/cluster/images/splice-test-docker-runner/Dockerfile"
runner_hook_file="${SPLICE_ROOT}/cluster/images/splice-test-runner-hook/Dockerfile"

sed \
  --in-place \
  --expression "s/^\(ARG RUNNER_VERSION=\).*/\1${runner_version}/" \
  --expression "s/^\(ARG RUNNER_DIGEST=\).*/\1${runner_digest}/"  \
  "${docker_runner_file}" \
  "${runner_hook_file}"

if git diff --exit-code --quiet "${docker_runner_file}" "${runner_hook_file}"; then
  echo "GHA runner version is up to date."
  exit 0
fi

git add --all
updated_branch="gha-runner-version-bump-$(date +%Y-%m-%d)"
git switch -c "${updated_branch}"
git commit -m "[static] bump GHA runner version to the latest (auto-generated)" -s
git push origin "${updated_branch}"

gh pr create \
  --base "main" \
  --head "$updated_branch" \
  --title "Bump GHA runner version to the latest (auto-generated)" \
  --reviewer isegall-da,martinflorian-da,ray-roestenburg-da,mblaze-da
