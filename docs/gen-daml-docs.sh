#!/usr/bin/env bash

set -eou pipefail

PROJ_ROOT="$(cd $(dirname "${BASH_SOURCE[0]}")/..; pwd)"

gen_project_docs () {
    (cd "$PROJ_ROOT/$1"
     daml build
     daml damlc docs --index-template $PROJ_ROOT/docs/api-templates/$2-index-template.rst $(find daml -name '*.daml') --exclude-modules '**.Scripts.**' -- -f rst -o $PROJ_ROOT/docs/src/app_dev/api/$2
    )
}

gen_project_docs canton-coin cc
gen_project_docs apps/wallet/daml wallet
gen_project_docs apps/directory/daml directory-service
