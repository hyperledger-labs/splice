#!/bin/bash

set -euo pipefail

# TODO (#10966): remove daml file from here
file_paths=("$REPO_ROOT/daml/dars.lock")

# Find latest commit where canton version or daml lock were modified
changed_files_hash=$(git log -n 1 --format="%H" -- "${file_paths[@]}")
# echo "Canton version or daml files were modified in commit: $changed_files_hash"

# Find latest commit containing "[breaking]"
breaking_message_hash=$(git log -n 1 --format="%H" --grep="\[breaking\]")
# echo "Explicit breaking changes were committed in commit: $breaking_message_hash"

# Return whatever is latest as base version
# echo "Latest base version commit is:"
if git merge-base --is-ancestor "$changed_files_hash" "$breaking_message_hash"; then
    echo "$breaking_message_hash"
else
    echo "$changed_files_hash"
fi
