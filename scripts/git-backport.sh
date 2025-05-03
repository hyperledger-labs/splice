#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# This script automates the creation of multiple backport branches & PRs based on a current branch changes.
# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

function git-backport() {
    if [ -z "$*" ]; then
        _info "Usage: git-backport.sh <branch_1> [<branch_n>]..."
        exit 0
    fi

    if [ -z "$GH_TOKEN" ]; then
        _error "GH_TOKEN is missing, add a personal access token with repo scope."
    fi

    if [ -n "$(git status --porcelain)" ]; then
        _error "Commit all changes before proceeding."
    fi

    originalbranch=$(git rev-parse --abbrev-ref HEAD)
    patchfile="/tmp/$originalbranch-backport.patch"

    function cleanup() {
        _info "Cleaning up..."
        git checkout "$originalbranch"
        git branch | grep -E " $originalbranch-.*" | xargs -r git branch -D
    }

    # Always drop the user back to the original branch in case of a script error
    trap cleanup ERR
    trap cleanup EXIT

    read -r -p "PR title: " pr_title
    read -r -p "PR reviewers (comma-separated): " reviewers
    read -r -p "Fixed issue numbers if any (comma-separated): " fixed_issues

    mkdir -p "$(dirname "$patchfile")"

    function pr_body() {
        local title="$1"
        local issues="$2"
        local refpr="$3"

        echo "$title"
        echo
        if [ -n "$refpr" ]; then
          echo "Backport of $refpr"
          echo
        fi
        for issue in ${issues//,/ }; do
          echo "Fixes #$issue"
        done
    }

    function push_pr() {
        local basebranch="$1"
        local workingbranch="$2"
        local title="$3"
        local issues="$4"
        local refpr="$5"

        git push --force-with-lease -u origin "$workingbranch"

        local prsearch; prsearch="$(gh pr list --head "$workingbranch")"

        _info "search is: $prsearch"

        if [ -z "$prsearch" ]; then
            _info "Creating PR for $workingbranch"
            gh pr create \
                --base "$basebranch" \
                --title "$title" \
                --body "$(pr_body "$title" "$issues" "$refpr")" \
                --reviewer "$reviewers"
        else
            _info "PR already exists for $workingbranch"
            return
        fi
    }

    _info "Calculating diff against 'main'. Diff summary:"
    local ancestor_commit; ancestor_commit=$(git merge-base origin/main "$originalbranch")

    git diff --compact-summary "$ancestor_commit"
    _confirm "Continue?"

    _info "Creating primary PR against main"
    push_pr main "$originalbranch" "$pr_title" "$fixed_issues" ""
    primary_pr="$(gh pr list --head "$originalbranch" --base main --json url | jq -r '.[].url')"

    for basebranch in "$@"
    do
        _info "Checking out $basebranch"
        git checkout "$basebranch"
        git pull

        workingbranch="$originalbranch-$basebranch"

        if [ -z "$(git branch --list "$workingbranch")" ]; then
            _info "Branch $workingbranch does not exist; creating..."
            git branch "$workingbranch"
        fi

        git checkout "$workingbranch"

        _info "Applying commits via cherry-pick"
        git cherry-pick "$ancestor_commit..$originalbranch"

        _info "Committing and pushing on $workingbranch"
        push_pr "$basebranch" "$workingbranch" "[backport] $pr_title ($basebranch)" "" "$primary_pr"

    done

    _info "Done backporting!"
    cleanup
}

git-backport "$@"
