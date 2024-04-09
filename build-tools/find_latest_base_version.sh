#!/bin/bash

set -euo pipefail

# TODO (#10966): remove daml file from here
# TODO (#11199): Remove pulumi and helm from here
file_paths=("$REPO_ROOT/daml/dars.lock" "$REPO_ROOT/cluster/pulumi" "$REPO_ROOT/cluster/helm")

# Find latest commit where canton version or daml lock were modified
changed_files_commit=$(git log -n 1 --format="%H %ct" -- "${file_paths[@]}")
IFS=" " read -r -a changed_files_arr <<< "$changed_files_commit"
changed_files_hash=${changed_files_arr[0]}
changed_files_epoch=${changed_files_arr[1]}
# echo "Canton version or daml files were modified on $changed_files_epoch in commit: $changed_files_hash"

# Find latest commit containing "[breaking]"
breaking_message_commit=$(git log -n 1 --format="%H %ct" --grep="\[breaking\]")
IFS=" " read -r -a breaking_message_arr <<< "$breaking_message_commit"
breaking_message_hash=${breaking_message_arr[0]}
breaking_message_epoch=${breaking_message_arr[1]}
# echo "Explicit breaking changes were committed on $breaking_message_epoch in commit: $breaking_message_hash"

# Return whatever is latest as base version
# echo "Latest base version commit is:"
if [[ "$breaking_message_epoch" > "$changed_files_epoch" ]]; then
    echo "$breaking_message_hash"
else
    echo "$changed_files_hash"
fi
