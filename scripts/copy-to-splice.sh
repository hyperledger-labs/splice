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
copy_dir "daml"
copy_dir "scripts/scan-txlog"
copy_file "scripts/external-signing/external-signing.py"
copy_dir "cluster/images"
copy_dir "cluster/helm"
copy_dir "cluster/compose"
copy_dir "openapi-templates"
copy_dir "cluster/pulumi/infra/grafana-dashboards"
copy_dir "network-health"
remove_dir "docs/src/app_dev"
copy_dir "docs/src/app_dev/daml_api"
cp "${SPLICE_ROOT}/docs/src/splice-index.rst" "${SPLICE_DIR}/docs/src/index.rst"
copy_dir "load-tester"

# Build code / configs
# Note that we are not currently copying LICENSE because the internal repo still has the
# proprietary license which we want to bundle in our release artifacts, until everything
# is made open source.
copy_file ".gitignore"
copy_dir "nix"
copy_file ".envrc"
copy_file ".envrc.vars"
copy_file "LATEST_RELEASE"
copy_file "VERSION"
copy_file "build-tools/lib/libcli.source"
copy_file "build-tools/get-snapshot-version"
copy_file "build-tools/npm-install.sh"
copy_file "build-tools/env-bool"
copy_file "scripts/check-repo-names.sh"
copy_file "scripts/transform-config.sc"
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
copy_dir "docs/api-templates"
copy_file "docs/gen-daml-docs.sh"
copy_file "docs/.gitignore"
copy_file "docs/src/conf.py"
copy_file "docs/livepreview.sh"
copy_file "daml.yaml"
copy_file "Makefile"
copy_file "cluster/local.mk"
copy_file "build-tools/overwrite-if-changed"
copy_file "scripts/canton-logback.xml"
copy_file "build-tools/version-tag-subst"
copy_dir "build-tools/wart-remover-extension"
