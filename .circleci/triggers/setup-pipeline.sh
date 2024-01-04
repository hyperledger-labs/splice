#!/usr/bin/env bash
# This script calls the CircleCI API to create / update the scheduled pipeline.
# The pipeline runs once a day and triggers certain workflows (i.e., continuous cluster deployment)

# Add your personal API token to the CIRCLECI_TOKEN environment variable before running the script.
# https://circleci.com/docs/managing-api-tokens

set -euo pipefail

# Upload or patch trigger schedules

for TRIGGER in "$REPO_ROOT"/.circleci/triggers/enabled/*.json
do
    "$REPO_ROOT/.circleci/triggers/install-trigger.sh" "${TRIGGER}"
done

# Delete unknown remote triggers

"$REPO_ROOT/.circleci/triggers/delete-unknown-triggers.sh"
