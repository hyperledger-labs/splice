#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

DOCS_DIR="$SPLICE_ROOT/docs"

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$SPLICE_ROOT/$1"
    local -a DAML_FILES
    readarray -t DAML_FILES < <(find daml -name '*.daml')
    dpm docs --index-template "$DOCS_DIR/api-templates/$2-index-template.rst" "${DAML_FILES[@]}" --exclude-modules '**.Scripts.**' -f rst -o "$DOCS_DIR/src/app_dev/api/$2"
    # Workaround for https://github.com/digital-asset/daml/pull/20889/files so we get toctrees again
    # shellcheck disable=SC2016
    find "$DOCS_DIR/src/app_dev/api/$2" -name '*.rst' -exec sed -i 's/^* :doc:`\(.*\)`$/   \1/g' {} +
)

# We explicitly exclude from the generated docs API packages that were released and must remain stable (thus are also not compiled any more)
# (make sure to commit the corresponding generated docs in a `docs` subfolder of the project)
NON_COMPILED_DAML_PROJECTS=(
    "$SPLICE_ROOT/daml/splice-api-featured-app-v1"
    "$SPLICE_ROOT/daml/splice-api-featured-app-v2"
    "$SPLICE_ROOT/token-standard/splice-api-token-allocation-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-transfer-instruction-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-allocation-instruction-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-allocation-request-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-metadata-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-holding-v1"
    "$SPLICE_ROOT/token-standard/splice-api-token-burn-mint-v1"
)

DAML_PROJECT_FILES="\
  $(find "$SPLICE_ROOT/daml" "$SPLICE_ROOT/token-standard" "$SPLICE_ROOT/token-standard/examples" -maxdepth 2 \
    \( -name target -o -name .daml -o -name src \) -prune -o -name daml.yaml \
    -not \( -ipath '*-test*' -not -ipath '*splice-token-standard-test*' -not -ipath '*test-trading-app*' \)  \
    -not -ipath '*splitwell*' \
    -not -ipath '*app-manager*' \
    -not -ipath '*dummy-holding*' \
    -print)"
DAML_PROJECT_FILES=$(printf "%s\n" "$DAML_PROJECT_FILES" | grep -vf <(printf "%s\n" "${NON_COMPILED_DAML_PROJECTS[@]}" | xargs -n1 basename))

for project_file in $DAML_PROJECT_FILES
do
    project_dir="$(dirname "${project_file#"$SPLICE_ROOT"/}")"
    gen_project_docs "$project_dir" "$(basename "$project_dir")"
done

# For projects in NON_COMPILED_DAML_PROJECTS, we just copy the checked in docs
for project_dir in "${NON_COMPILED_DAML_PROJECTS[@]}"
do
  project_name="$(basename "$project_dir")"
  echo "(docs) copying $project_name"
  mkdir -p "$DOCS_DIR/src/app_dev/api/$project_name"
  cp "$project_dir"/docs/* "$DOCS_DIR/src/app_dev/api/$project_name/"
done
