#!/usr/bin/env bash

set -eou pipefail

PROJ_ROOT="$(cd $(dirname "${BASH_SOURCE[0]}")/../../..; pwd)"

gen_project_docs () (
    echo "(docs) generating $1"
    cd "$PROJ_ROOT/$1"
     "${XDG_CACHE_HOME:-$HOME/.cache}/daml-build/${SDK_VERSION}/damlc/damlc" docs --index-template $PROJ_ROOT/cluster/images/docs/api-templates/$2-index-template.rst $(find daml -name '*.daml') --exclude-modules '**.Scripts.**' -f rst -o $PROJ_ROOT/cluster/images/docs/src/app_dev/api/$2
)

# skip calling sbt when `SKIP_DAML_BUILD` is defined
if [[ ! -v SKIP_DAML_BUILD ]]; then
  (cd $PROJ_ROOT; sbt --batch canton-coin-daml/damlBuild wallet-payments-daml/damlBuild directory-daml/damlBuild)
fi

gen_project_docs daml/canton-coin cc
gen_project_docs daml/wallet-payments wallet
gen_project_docs daml/directory-service directory-service
