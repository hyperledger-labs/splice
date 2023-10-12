#!/usr/bin/env bash
set -eou pipefail

echo "Creating DBs for CN apps"

cn_app_names=(
  "cn_directory"
)

# Create the DB's in parallel
printf '%s\n' "${cn_app_names[@]}" | xargs -P 64 -I {} ./scripts/postgres.sh "$POSTGRES_MODE" createdb {}
