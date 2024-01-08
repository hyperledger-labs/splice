#!/usr/bin/env bash

set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

TARGET_DIR=../daml/upgrade-diffs

if [ "$#" -ge 1 ]; then
    TARGET_DIR="$1"
fi

diff_project() {
  local project_name="$1"
  # gnu diff includes dumb timestamps in the diff result so we `sed` them away.
  # this is great.
  diff -ur -x '*~' -x target -x dar -x .daml "../daml/$project_name" "../daml/$project_name-upgrade" | sed -E 's/^((---|\+\+\+) [^[:space:]]+).*/\1/g' > "$TARGET_DIR/$project_name.diff" || true
}

projects=(
    canton-coin
    canton-coin-test
    wallet
    wallet-test
    wallet-payments
    svc-governance
    svc-governance-test
    canton-name-service
    canton-name-service-test
    splitwell
    splitwell-test
)

for project in "${projects[@]}"; do
    diff_project "$project"
done
