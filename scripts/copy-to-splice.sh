#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <splice-dir>"
  exit 1
fi

SPLICE_DIR=$1

if [ ! -d "$SPLICE_DIR" ]; then
  echo "Error: $SPLICE_DIR is not a directory"
  exit 1
fi

function copy_dir() {
  local path=$1

  dir=$(dirname "$path")
  name=$(basename "$path")

  mkdir -p "${SPLICE_DIR}/${dir}"

  rsync -ah --delete "${SPLICE_ROOT}/${dir}/${name}" "${SPLICE_DIR}/${dir}" \
    --exclude-from=<(git -C "${SPLICE_ROOT}/${dir}/${name}" ls-files --exclude-standard -oi --directory)
}

function remove_dir() {
  local path=$1

  dir=$(dirname "$path")
  name=$(basename "$path")

  rm -rf "${SPLICE_DIR:?}/${dir}"
}

function copy_file() {
  local path=$1

  dir=$(dirname "$path")
  name=$(basename "$path")

  mkdir -p "${SPLICE_DIR}/${dir}"
  cp -a "${SPLICE_ROOT}/${path}" "${SPLICE_DIR}/${dir}"
}

# Source code
copy_dir "apps"
copy_dir "canton"
copy_dir "token-standard"
copy_dir "daml"
copy_file "build-tools"
copy_dir "scripts"
copy_dir "cluster/images"
copy_dir "cluster/helm"
copy_dir "cluster/compose"
copy_dir "openapi-templates"
copy_dir "cluster/pulumi/infra/grafana-dashboards"
copy_dir "network-health"
copy_dir "load-tester"

# Build code / configs
# Note that we are not currently copying LICENSE because the internal repo still has the
# proprietary license which we want to bundle in our release artifacts, until everything
# is made open source.
copy_file ".gitignore"
copy_dir "nix"
copy_file ".envrc"
copy_file ".envrc.vars"
copy_file ".envrc.validate"
copy_file "LATEST_RELEASE"
copy_file "VERSION"
copy_file "create-bundle.sh"
copy_file "build.sbt"
# sbt creates directory project/project, which confuses the rsync-gitignore function,
# so we just copy the relevant files and subdirectories from `project` individually
copy_dir "project/ignore-patterns"
for f in project/*; do
  if [ -f "${SPLICE_ROOT}/$f" ]; then
    copy_file "$f"
  fi
done
copy_dir "docs"
copy_file "daml.yaml"
copy_file "Makefile"
copy_file "cluster/local.mk"
copy_dir ".github/actions/scripts"

copy_file ".editorconfig"
copy_file ".gitmodules"
copy_dir ".idea"
copy_dir ".k9s"
copy_file ".pre-commit-config.yaml"
copy_file ".prettierignore"
copy_file ".scalafix.conf"
copy_file ".scalafmt.conf"
copy_file ".vscode"
copy_file "bootstrap-canton.sc"
copy_dir "readme"
copy_file "set-sdk.sh"
copy_file "start-canton.sh"
copy_file "start-frontends.sh"
copy_file "stop-canton.sh"
copy_file "stop-frontends.sh"
copy_file "wait-for-canton.sh"
copy_dir "support"
cp "${SPLICE_ROOT}"/test-*.log "${SPLICE_DIR}/"
copy_file "util.sh"

# Cleanup of directories we used to copy to Splice, but no longer do,
# so they don't get removed by rsync.
rm -rf "${SPLICE_DIR}/.circleci"
rm -rf "${SPLICE_DIR}/cluster/deployment/compose"
rm -rf "${SPLICE_DIR}/images"

# Since we are explicitly specifying what to copy above, rather than what not
# to copy, we test that we are not missing anything from Splice that was not
# expected to be missing, so that we catch if people mistakenly introduce files
# not captured above.
# Note that the list of expected to be missing will shrink over the coming days,
# until we are ready to fully move to Splice.

unknown=$(diff -qr . "${SPLICE_DIR}" |
    sed 's/^Only in //g' |
    grep -v '^\.:' |
    grep -v '^\./cluster/pulumi' |
    grep -v '/\.git[/:]' |
    grep -v '/\.github[/:]' |
    grep -v '\./cluster' |
    grep -v 'LICENSE.*differ' |
    grep -v 'README.md.*differ' |
    grep -v '\.gitattributes.*differ' |
    grep -v 'CODEOWNERS.*differ' || true)

echo "Unexpected files missing from Splice: $unknown"

unknown=$(diff -qr . "${SPLICE_DIR}" |
    sed 's/^Only in //g' | grep '^\.:' | sed 's/^\.: //g' |
    grep -v '.circleci' |
    grep -v '.direnv' |
    grep -v 'CODEOWNERS' |
    grep -v 'LICENSE' |
    grep -v '.*.md' |
    grep -v 'wait-for-canton.sh' |
    grep -v 'openapi-cache-key.txt' |
    grep -v '^\.git' || true)

echo "Unexpected files missing from Splice: $unknown"
