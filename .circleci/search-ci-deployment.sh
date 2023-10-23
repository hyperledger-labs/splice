#!/usr/bin/env bash
set -eou pipefail

# Add your personal Circle CI API token to the CIRCLECI_TOKEN environment variable before running the script.
function usage() {
  echo "Usage: ./search-ci-deployment.sh <flags>"
  echo "Flags:"
  echo "  -h               Add your personal cci API token to the CIRCLECI_TOKEN environment variable before running the script"
  echo "  -n               name of a cci workflow"
  echo "  -l               number of latest runs to display"
}

CCI_PROJECT="github/DACH-NY/canton-network-node"
BRANCH_NAME="main"
LIMIT_RUNS=1

while getopts "h:n:l:" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    n)
      WORKFLOW_NAME="${OPTARG}"
      ;;
    l)
      LIMIT_RUNS="${OPTARG}"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

recent_runs_of_workflow=$(curl -s -u "${CIRCLECI_TOKEN}": -H "Content-Type: application/json" \
    "https://circleci.com/api/v2/insights/${CCI_PROJECT}/workflows/${WORKFLOW_NAME}?all-branches=false&branch=${BRANCH_NAME}")

for ((run=0; run<LIMIT_RUNS; run++)); do

  echo "${recent_runs_of_workflow}" | jq -r ".items[${run}]"
  workflow_id=$(echo "${recent_runs_of_workflow}" | jq -r ".items[${run}].id")

  workflow_by_id=$(curl -s -u "${CIRCLECI_TOKEN}": -H "Content-Type: application/json" \
      https://circleci.com/api/v2/workflow/"${workflow_id}")

  pipeline_number=$(echo "${workflow_by_id}" | jq -r '.pipeline_number')

  echo Link of "${WORKFLOW_NAME}" last deployment: \
      "https://app.circleci.com/pipelines/${CCI_PROJECT}/${pipeline_number}/workflows/${workflow_id}"

done
