#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eoux pipefail

if [ -n "${CI:-}" ]; then
  git config user.email "splice-maintainers@digitalasset.com"
  git config user.name "DA Automation"
  git remote set-url origin "https://${GH_USER}:${GH_TOKEN}@github.com/DACH-NY/canton-network-node.git"
fi
BRANCH_NAME="deploy-cilr"
git branch -f "$BRANCH_NAME" HEAD
git checkout "$BRANCH_NAME"
VERSION="$(build-tools/get-snapshot-version)"
cd cluster/deployment/cilr
. .envrc.vars
# Note that we set this here rather than setting the cluster argument to run_bash_command_in_nix.
# The latter would result in $VERSION above referring to the version cilr is currently on which is not what we want.
TARGET_CLUSTER=cilr cncluster update_active_version "$VERSION"
git add .
git commit -m "Update CILR to $VERSION"
git push -f origin "$BRANCH_NAME"
git tag -f cilr
git push -f origin cilr
