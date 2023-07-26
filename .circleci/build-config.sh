#!/usr/bin/env bash

set -eu -o pipefail

if [ "${1-}" == "--check" ]; then
    check=yes
fi

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

# get the full path to the directory
if [ -n "${check-}" ] ; then
    OUTPUT_CONF="${REPO_ROOT}/.circleci/config-check-output.yml"
    _info "Checking Configuration"
else
    OUTPUT_CONF="${REPO_ROOT}/.circleci/config.yml"
    _info "Building Configuration"
fi


{
    echo '# GENERATED FILE, DO NOT EDIT'
    echo '# If you need to change the CircleCI configuration, edit the'
    echo '# relevant fragment in the .circleci/config directory. and'
    echo '# Run .circleci/build-config.sh to update this file.'
    echo '#'
    echo ''

    cat "${REPO_ROOT}/.circleci/config/prelude.yml"
    cat "${REPO_ROOT}/.circleci/config/commands.yml"
    cat "${REPO_ROOT}/.circleci/config/jobs.yml"
    cat "${REPO_ROOT}/.circleci/config/workflows.yml"
} > "${OUTPUT_CONF}"

for clustername in scratchneta scratchnetb scratchnetc scratchnetd scratchnete scratchnetf scratchnetg
do
    sed "s/_CLUSTERNAME_/${clustername}/g" \
        < "${REPO_ROOT}/.circleci/config/deploy_scratchnet_workflow.yml" \
        >> "${OUTPUT_CONF}"
done

if [ -n "${check-}" ] ; then
    if ! diff -u "${REPO_ROOT}/.circleci/config-check-output.yml" "${REPO_ROOT}/.circleci/config.yml"; then
        _error "Config files do not match, please rerun .circleci/build-config.sh, recommit, and try again."
    fi

else
    _info "Validating Configuration"

    circleci config validate "${OUTPUT_CONF}"

    _info "Configuration file built and validated against CircleCI YAML schema."
fi
