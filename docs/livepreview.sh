#!/usr/bin/env bash
set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

(cd "$REPO_ROOT"; sbt --batch damlBuild)
./gen-daml-docs.sh

sphinx-autobuild src html/html
