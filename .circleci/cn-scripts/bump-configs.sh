#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# TODO(#18449) Remove this after all clusters are on >= 0.3.16
# Backwards compatibility for bumping old branches where make update-expected fails otherwise.
export REPO_ROOT="$SPLICE_ROOT"

function bump_in_branch() {
  branch=$1
  bump_branch="bump-$branch-$(date +%s)"

  git fetch origin "$branch"
  git checkout -b "$bump_branch" "refs/remotes/origin/$branch"
  git submodule update --remote
  git add -u

  diff=$(git diff --submodule=diff "refs/remotes/origin/$branch")
  if [ -z "$diff" ]; then
    echo "No changes in $branch, nothing to do"
    return
  fi

  # Add full diff to the PR body if it's not too long, otherwise do --stat
  if [ ${#diff} -lt 30000 ]; then
    cat <<EOF > /tmp/pr-body
Diff:
\`\`\`
$diff
\`\`\`

EOF
  else
    diffstat=$(git diff --submodule=diff --stat "refs/remotes/origin/$branch")
    cat <<EOF > /tmp/pr-body
Diff (--stat):
\`\`\`
$diffstat
\`\`\`

EOF
  fi

  changed=$(git diff --name-only "refs/remotes/origin/$branch")
  for submodule in $changed; do

    current=$(git ls-tree "refs/remotes/origin/$branch" "$submodule" | awk '{print $3}')
    head=$(git --git-dir "$submodule/.git" rev-parse HEAD)
    github_url=$(git --git-dir="$submodule/.git" remote -v | head -1 | awk '{print $2}' | sed 's/git@github.com:/https:\/\/github.com\//' | sed 's/\.git$/\/compare\/'"$current..$head"'/')
    cat <<EOF >> /tmp/pr-body

Github compare for $submodule: $github_url

Git log for $submodule:
\`\`\`
$(git --git-dir "$submodule/.git" log --oneline "$current"..HEAD)
\`\`\`

EOF
  done

  make cluster/pulumi/update-expected -j8
  git add -u
  git commit -m "[static] Bump submodules to latest"
  git push origin "$bump_branch"

  gh pr create \
    --base "$branch" \
    --head "$bump_branch" \
    --title "($branch) Bump submodules to latest (auto-generated)" \
    --reviewer isegall-da,moritzkiefer-da,nicu-da,martinflorian-da,ray-roestenburg-da \
    --body-file /tmp/pr-body

  git checkout -
}

# ssh is not in the path in nix in CCI, so helping git find it
export GIT_SSH_COMMAND="/usr/bin/ssh"
# Dirty repos break get-snapshot-version on CI unless explicitly ignored
export CI_IGNORE_DIRTY_REPO=1
git config user.email "team-canton-network@digitalasset.com"
git config user.name "DA Automation"

echo "main" > /tmp/branches
echo "release-line-$(cat LATEST_RELEASE)" >> /tmp/branches
for net in devnet testnet mainnet; do
  yq '.synchronizerMigration.active.releaseReference.gitReference' < "cluster/deployment/$net/config.yaml" | sed s'/refs\/heads\///' >> /tmp/branches
done

sort /tmp/branches | uniq | while read -r branch; do
  bump_in_branch "$branch"
done
