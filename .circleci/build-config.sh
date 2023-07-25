#!/usr/bin/env bash

set -eu -o pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

# get the full path to the directory
CONF="${REPO_ROOT}/.circleci/generated_config.yml"

{
    echo '# GENERATED FILE, DO NOT EDIT'
    echo '# If you need to change the CircleCI configuration, edit the'
    echo '# relevant fragment in the .circleci/config directory.'

    cat "${REPO_ROOT}/.circleci/config/prelude.yml"
    cat "${REPO_ROOT}/.circleci/config/commands.yml"
    cat "${REPO_ROOT}/.circleci/config/jobs.yml"
    cat "${REPO_ROOT}/.circleci/config/workflows.yml"
} > "${CONF}"

for clustername in scratchneta scratchnetb scratchnetc scratchnetd scratchnete scratchnetf scratchnetg
do
    sed "s/_CLUSTERNAME_/${clustername}/g" \
        < "${REPO_ROOT}/.circleci/config/deploy_scratchnet_workflow.yml" \
        >> "${CONF}"
done
