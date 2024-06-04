#!/usr/bin/env bash

set -eou pipefail

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$REPO_ROOT/$1"
    local -a DAML_FILES
    readarray -t DAML_FILES < <(find daml -name '*.daml')
    "${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${DAML_COMPILER_VERSION}/damlc/damlc" docs --index-template "$REPO_ROOT/cluster/images/docs/api-templates/$2-index-template.rst" "${DAML_FILES[@]}" --exclude-modules '**.Scripts.**' -f rst -o "$REPO_ROOT/cluster/images/docs/src/app_dev/api/$2"
    # Workaround to fix indentation issues in rst output due to https://github.com/digital-asset/daml/issues/16956
    find "$REPO_ROOT/cluster/images/docs/src/app_dev/api/$2" -name '*.rst' -exec sed -i -z 's!\( *\)\(Controller\\: [^\n]*map\)\n *\([^\n]*\)\n *\([^\n]*\)\n!\1\2\n\1\3\n\1\4\n!g' {} +
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

# skip calling sbt when `SKIP_DAML_BUILD` is defined
if [[ ! -v SKIP_DAML_BUILD ]]; then
  (cd "$REPO_ROOT"; sbt --batch damlBuild)
fi

ensure_damlc_exists

DAML_PROJECT_FILES="$(find "$REPO_ROOT/daml" -name daml.yaml -not -ipath '*-test*' -not -ipath '*splitwell*' -not -ipath '*app-manager*')"
for project_file in $DAML_PROJECT_FILES
do
    project="$(basename "$(dirname "$project_file")")"
    gen_project_docs "daml/$project" "$project"
done
