# This script calls the CircleCI API to create / update the scheduled pipeline.
# The pipeline runs once a day and triggers certain workflows (i.e., continuous cluster deployment)

# Add your personal API token to the CIRCLECI_TOKEN environment variable before running the script.
# https://circleci.com/docs/managing-api-tokens

#!/usr/bin/env bash
set -euo pipefail

# $REPO_ROOT/.circleci/install-trigger.sh $REPO_ROOT/.circleci/run-schedule-pipeline.json

for TRIGGER in $REPO_ROOT/.circleci/trigger-*.json
do
    $REPO_ROOT/.circleci/install-trigger.sh ${TRIGGER}
done
