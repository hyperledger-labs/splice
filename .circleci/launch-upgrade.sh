#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

function usage() {
  echo "Usage: ./launch-upgrade.sh --cluster <cluster> --branch-filter-wait <optional flags>"
  echo "Requires CIRCLECI_TOKEN to be set in the environment."
  echo "Flags:"
  echo "  -h                              display this help message"
  echo "  --branch <branch_name>          branch to upgrade to. (default: CIRCLE_BRANCH or result of \"git rev-parse --abbrev-ref HEAD\")"
  echo "  --branch-filter-wait <filter>   parameter for wait_for_previous_pipeline (default: same as branch)"
  echo "  --base-version  <base_version>  set base version. (default: result of find_latest_base_version)"
  echo "  --cluster <cluster>             choose the cluster in which to run. (e.g. ciupgrade, scratchneta)"
  echo "  --run-daml-upgrade              Whether to run daml upgrades. Recommended when daml code was changed. (default: output of build-tools/changes-vs-base \"\$REPO_ROOT/daml/dars.lock\")"
  echo "  --run-partial-upgrade           Whether to run a partial upgrade. See cncluster partial_upgrade for more information. (default: output of build-tools/changes-vs-base \"\$REPO_ROOT/cluster\")"
}

if [[ -z ${CIRCLECI_TOKEN:-} ]]; then
  _error "CIRCLECI_TOKEN is not set."
fi

args=$(getopt -o "h" -l "base-version:,cluster:,branch:,branch-filter-wait:,run-daml-upgrade,run-partial-upgrade,help" -- "$@")

eval set -- "$args"

while true
do
    case "$1" in
        -h)
            usage
            exit 0
            ;;
        --help)
            usage
            exit 0
            ;;
        --base-version)
            base_version="$2"
            shift
            ;;
        --cluster)
            cluster="$2"
            shift
            ;;
        --branch)
            branch="$2"
            shift
            ;;
        --branch-filter-wait)
            branch_filter="$2"
            shift
            ;;
        --run-daml-upgrade)
            run_daml_upgrade="true"
            ;;
        --run-partial-upgrade)
            run_partial_upgrade="true"
            ;;
        --)
            shift
            break
            ;;
    esac
    shift
done

if [ -z "${cluster-}" ]; then
  echo "Missing --cluster <cluster>"
  usage
  exit 1
fi

if [ -z "${base_version-}" ]; then
  base_version=$("$REPO_ROOT"/build-tools/find_latest_base_version.sh)
  echo "Base version set to $base_version"
fi

if [ -z "${branch-}" ]; then
  branch="${CIRCLE_BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
  echo "Upgrading to HEAD of $branch"
fi

if [ -z "${branch_filter-}" ]; then
  branch_filter="$branch"
fi
echo "Branch filter set to $branch_filter"

if [ -z "${run_daml_upgrade-}" ]; then
  if "$REPO_ROOT"/build-tools/changes-vs-base "$REPO_ROOT/daml/dars.lock"; then
    echo "Skipping cluster daml upgrade."
    run_daml_upgrade="false"
  else
    echo "Proceeding with daml upgrade."
    run_daml_upgrade="true"
  fi
fi

if [ -z "${run_partial_upgrade-}" ]; then
  if "$REPO_ROOT"/build-tools/changes-vs-base "$REPO_ROOT/cluster" && "$REPO_ROOT"/build-tools/changes-vs-base "$REPO_ROOT/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/runbook"; then
    echo "Proceeding with partial upgrade."
    run_partial_upgrade="true"
  else
    echo "Skipping cluster partial upgrade and preflights steps."
    run_partial_upgrade="false"
  fi
fi

data="{\"branch\": \"$branch\", \"parameters\":{\"run-job\":\"deploy-upgrade\", \"branch-filter\": \"$branch_filter\", \"cluster\": \"$cluster\", \"run-partial-upgrade\": $run_partial_upgrade, \"run-daml-upgrade\": $run_daml_upgrade, \"base-version\": \"$base_version\"}}"
echo "Running CircleCI request:"
jq -n "$data"

curl --fail-with-body --request POST \
  --url https://circleci.com/api/v2/project/github/DACH-NY/canton-network-node/pipeline \
  --header "Circle-Token: ${CIRCLECI_TOKEN}" \
  --header 'content-type: application/json' \
  --data "$data"
