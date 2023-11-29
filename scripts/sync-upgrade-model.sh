#!/usr/bin/env bash

set -eoux pipefail

cd "$REPO_ROOT"

# This is effectively git rebase without a git repo.
sync_project() {
  local project_name="$1"
  git apply "$REPO_ROOT/daml/upgrade-diffs/$project.diff" --directory daml -p 2 -R
  rsync -av --delete "daml/${project_name}/" "daml/${project_name}-upgrade"
  git apply "$REPO_ROOT/daml/upgrade-diffs/$project.diff" --directory daml -p 2 --reject
}

projects=(
    canton-coin
    wallet
    wallet-payments
    svc-governance
    canton-name-service
    splitwell
)

for project in "${projects[@]}"; do
    sync_project "$project"
done

# Recreate the diffs to catch potentially updated line numbers.
./scripts/update-upgrade-model-diff.sh
