#!/bin/bash

set -euo pipefail

# Get the commit for the latest release
latest_release=$(cat "LATEST_RELEASE")
latest_release_hash=$(git rev-parse heads/origin/release-line-"${latest_release}")

# Find latest commit containing "[breaking]"
breaking_message_hash=$(git log -n 1 --format="%H" --grep="\[breaking\]")
# echo "Explicit breaking changes were committed in commit: $breaking_message_hash"

# Return whatever is latest as base version
if git merge-base --is-ancestor "$latest_release_hash" "$breaking_message_hash"; then
    echo "$breaking_message_hash"
else
    echo "$latest_release_hash"
fi
