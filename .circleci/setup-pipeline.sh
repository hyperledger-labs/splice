# This script calls the CircleCI API to create / update the scheduled pipeline.
# The pipeline runs once a day and triggers certain workflows (i.e., continuous cluster deployment)

# Add your personal API token to the CIRCLECI_TOKEN environment variable before running the script.
# https://circleci.com/docs/managing-api-tokens

#!/usr/bin/env bash
set -euo pipefail

set +u
if [[ -z $CIRCLECI_TOKEN ]]; then
    echo "Error: \$CIRCLECI_TOKEN variable is not set."
    echo "Create and set your API token: https://circleci.com/docs/managing-api-tokens"
    exit 1
fi
set -u

verb=POST
endpoint="https://circleci.com/api/v2/project/github/DACH-NY/the-real-canton-coin/schedule"

existing_schedule=$(curl -s "$endpoint" \
    -H "Content-Type: application/json" \
    -H "circle-token: $CIRCLECI_TOKEN" | jq -c '.items[] | select(.name | contains("Nightly deployment"))')

if [[ -n $existing_schedule ]]; then
    echo "Schedule already exists... patching"
    schedule_id=$(echo $existing_schedule | jq -r .id)

    verb=PATCH
    endpoint="https://circleci.com/api/v2/schedule/$schedule_id"
fi

curl -X $verb $endpoint \
    -d @$REPO_ROOT/.circleci/run-schedule-pipeline.json \
    -H "Content-Type: application/json" \
    -H "circle-token: $CIRCLECI_TOKEN"
