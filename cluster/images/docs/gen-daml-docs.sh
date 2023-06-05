#!/usr/bin/env bash

set -eou pipefail

PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.."; pwd)"

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$PROJ_ROOT/$1"
    local -a DAML_FILES
    readarray -t DAML_FILES < <(find daml -name '*.daml')
    "${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${SDK_VERSION}/damlc/damlc" docs --index-template "$PROJ_ROOT/cluster/images/docs/api-templates/$2-index-template.rst" "${DAML_FILES[@]}" --exclude-modules '**.Scripts.**' -f rst -o "$PROJ_ROOT/cluster/images/docs/src/app_dev/api/$2"
    # Workaround to fix indentation issues in rst output due to https://github.com/digital-asset/daml/issues/16956
    find "$PROJ_ROOT/cluster/images/docs/src/app_dev/api/$2" -name '*.rst' -exec sed -i -z 's!\( *\)\(Controller\\: [^\n]*map\)\n *\([^\n]*\)\n *\([^\n]*\)\n!\1\2\n\1\3\n\1\4\n!g' {} +
)

# skip calling sbt when `SKIP_DAML_BUILD` is defined
if [[ ! -v SKIP_DAML_BUILD ]]; then
  (cd "$PROJ_ROOT"; sbt --batch canton-coin-daml/damlBuild wallet-payments-daml/damlBuild directory-daml/damlBuild)
fi

gen_project_docs daml/canton-coin cc
gen_project_docs daml/wallet-payments wallet
gen_project_docs daml/directory-service directory-service
