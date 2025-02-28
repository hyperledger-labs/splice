#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

DOCS_DIR="$REPO_ROOT/docs"

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$REPO_ROOT/$1"
    local -a DAML_FILES
    readarray -t DAML_FILES < <(find daml -name '*.daml')
    "${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${DAML_COMPILER_VERSION}/damlc/damlc" docs --index-template "$DOCS_DIR/api-templates/$2-index-template.rst" "${DAML_FILES[@]}" --exclude-modules '**.Scripts.**' -f rst -o "$DOCS_DIR/src/app_dev/api/$2"
    # Workaround to fix indentation issues in rst output due to https://github.com/digital-asset/daml/issues/16956
    find "$DOCS_DIR/src/app_dev/api/$2" -name '*.rst' -exec sed -i -z 's!\( *\)\(Controller\\: [^\n]*map\)\n *\([^\n]*\)\n *\([^\n]*\)\n!\1\2\n\1\3\n\1\4\n!g' {} +
)

ensure_damlc_exists() {
    if [ ! -f "${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${DAML_COMPILER_VERSION}/damlc/damlc" ]; then
        dir=$HOME/.cache/daml-build/${DAML_COMPILER_VERSION}/
        if [ "$(uname -s)" == "Linux" ]; then
            os="linux-intel"
        else
            os="macos"
        fi
        mkdir -p "$dir"
        curl -sSL --fail -o "$dir"/damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz https://storage.googleapis.com/daml-binaries/split-releases/"$DAML_COMPILER_VERSION"/damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz
        pushd "$dir"
        tar -zxf damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz
        popd
    fi
}

ensure_damlc_exists

DAML_PROJECT_FILES="$(find "$REPO_ROOT/daml" "$REPO_ROOT/token-standard" -maxdepth 2 \( -name target -o -name .daml -o -name src \) -prune -o -name daml.yaml -not -ipath '*-test*' -not -ipath '*splitwell*' -not -ipath '*app-manager*' -print)"
for project_file in $DAML_PROJECT_FILES
do
    project_dir="$(dirname "${project_file#"$REPO_ROOT"/}")"
    gen_project_docs "$project_dir" "$(basename "$project_dir")"
done
