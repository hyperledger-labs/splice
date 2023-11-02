#!/usr/bin/env bash
set -eou pipefail

POSTGRES_MODE=docker # drop & create are the same for all modes

echo "Dropping DB for CN apps"
"${REPO_ROOT}"/scripts/postgres.sh "$POSTGRES_MODE" dropdb cn_apps

echo "Recreating DB for CN apps"
"${REPO_ROOT}"/scripts/postgres.sh "$POSTGRES_MODE" createdb cn_apps
