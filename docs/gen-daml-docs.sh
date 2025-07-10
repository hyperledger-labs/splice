#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

DOCS_DIR="$SPLICE_ROOT/docs"
DAMLC_VERSION_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${DAML_COMPILER_VERSION}"

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$SPLICE_ROOT/$1"
    local -a DAML_FILES
    readarray -t DAML_FILES < <(find daml -name '*.daml')
    "$DAMLC_VERSION_DIR/damlc/damlc" docs --index-template "$DOCS_DIR/api-templates/$2-index-template.rst" "${DAML_FILES[@]}" --exclude-modules '**.Scripts.**' -f rst -o "$DOCS_DIR/src/app_dev/api/$2"
    # Workaround for https://github.com/digital-asset/daml/pull/20889/files so we get toctrees again
    # shellcheck disable=SC2016
    find "$DOCS_DIR/src/app_dev/api/$2" -name '*.rst' -exec sed -i 's/^* :doc:`\(.*\)`$/   \1/g' {} +
)

ensure_damlc_exists() {
    if [[ ! -f $DAMLC_VERSION_DIR/damlc/damlc
          || ! -d $DAMLC_VERSION_DIR/damlc/resources ]]; then
        case "$(uname -s)" in
            Linux) os="linux-intel";;
            Darwin) os="macos";;
            *)
                echo "Unsupported OS for damlc download: $(uname -s)" >&2
                exit 1;;
        esac
        mkdir -p "$DAMLC_VERSION_DIR"
        curl -sSL --fail -o "$DAMLC_VERSION_DIR"/damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz \
            https://storage.googleapis.com/daml-binaries/split-releases/"$DAML_COMPILER_VERSION"/damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz
        pushd "$DAMLC_VERSION_DIR"
        tar -zxf damlc-"$DAML_COMPILER_VERSION"-"$os".tar.gz
        popd
    fi
}

ensure_damlc_exists

DAML_PROJECT_FILES="\
  $(find "$SPLICE_ROOT/daml" "$SPLICE_ROOT/token-standard" "$SPLICE_ROOT/token-standard/examples" -maxdepth 2 \
    \( -name target -o -name .daml -o -name src \) -prune -o -name daml.yaml \
    -not \( -ipath '*-test*' -not -ipath '*splice-token-standard-test*' -not -ipath '*test-trading-app*' \)  \
    -not -ipath '*splitwell*' \
    -not -ipath '*app-manager*' \
    -not -ipath '*dummy-holding*' \
    -print)"
for project_file in $DAML_PROJECT_FILES
do
    project_dir="$(dirname "${project_file#"$SPLICE_ROOT"/}")"
    gen_project_docs "$project_dir" "$(basename "$project_dir")"
done
