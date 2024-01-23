#!/usr/bin/env bash

set -eoux pipefail

cd "$REPO_ROOT"

# This is effectively git rebase without a git repo.
sync_project() {
  local project_name="$1"
  git apply "$REPO_ROOT/daml/upgrade-diffs/$project.diff" -p 0 -R
  rsync -av --delete --exclude-from=.gitignore "daml/${project_name}/" "daml/${project_name}-upgrade"
  # Proceed on rejection to ensure all projects are synced, and the `wiggle` CLI tool can be used afterwards to resolve the rejections
  git apply "$REPO_ROOT/daml/upgrade-diffs/$project.diff" -p 0 --reject || true
}

readarray -t projects < <(./scripts/lib/get-upgraded-daml-projects.sh)

for project in "${projects[@]}"; do
    echo "Syncing $project..."
    sync_project "$project"
done

# Recreate the diffs to catch potentially updated line numbers.
./scripts/update-upgrade-model-diff.sh
