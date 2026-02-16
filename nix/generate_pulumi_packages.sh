#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# shellcheck shell=bash
# Bash 3 compatible for Darwin
# Based on https://github.com/NixOS/nixpkgs/blob/master/pkgs/tools/admin/pulumi-bin/update.sh


set -euo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

declare -a plugins
plugins=(
  # currently used
  "pulumi/gcp=9.10.0"
  "pulumi/kubernetes=4.25.0"
  "pulumi/random=4.19.0"
  "pulumi/std=2.2.0"
  "pulumi/auth0=3.36.0"
  "pulumi/command=1.1.3"
  "pulumi/kubernetes-cert-manager=0.2.0"
  "pulumiverse/grafana=0.16.3"
  # old versions so that old pulumi state can be interpreted
  # each can be removed once MainNet uses a newer version
  "pulumi/kubernetes=4.23.0"
  "pulumi/kubernetes=4.22.0"
  "pulumi/random=4.18.2"
  "pulumi/auth0=3.21.0"
  "pulumi/command=1.1.0"
)

function genSrc() {
  local url="${1}"
  local plug="${2}"
  local tmpdir="${3}"

  local sha256
  sha256=$(wget -qO- "$url" | sha256sum | awk '{ print $1 }')

  {
    if [ -n "$sha256" ]; then # file exists
      echo "      {"
      echo "        url = \"${url}\";"
      echo "        sha256 = \"$sha256\";"
      echo "      }"
    else
      echo "      # pulumi-resource-${plug} skipped (does not exist on remote)"
    fi
  } > "${tmpdir}/${plug}.nix"
}

function genSrcs() {
  local tmpdir
  tmpdir="$(mktemp -d)"

  for plugVers in "${plugins[@]}"; do
    local srcplug=${plugVers%=*}
    local src=${srcplug%/*}
    local plug=${srcplug#*/}
    local version=${plugVers#*=}
    # url as defined here
    # https://github.com/pulumi/pulumi/blob/06d4dde8898b2a0de2c3c7ff8e45f97495b89d82/pkg/workspace/plugins.go#L197
    if [ "$src" == "pulumi" ]; then
      local url="https://github.com/pulumi/pulumi-${plug}/releases/download/v${version}/pulumi-resource-${plug}-v${version}-${1}-${2}.tar.gz"
    elif [ "$src" == "pulumiverse" ]; then
      local url="https://github.com/pulumiverse/pulumi-${plug}/releases/download/v${version}/pulumi-resource-${plug}-v${version}-${1}-${2}.tar.gz"
    else
      echo "Unknown source: $src" >&2
      exit 1
    fi
    genSrc "${url}" "${plug}-${version}" "${tmpdir}" &
  done

  wait

  find "${tmpdir}" -name '*.nix' -print0 | sort -z | xargs -r0 cat
  rm -r "${tmpdir}"
}

{
  cat << EOF
# DO NOT EDIT! This file is generated automatically by generate_pulumi_packages.sh
{ }:
{
  packages = {
EOF

  echo "    x86_64-linux = ["
  genSrcs "linux" "amd64"
  echo "    ];"

  echo "    x86_64-darwin = ["
  genSrcs "darwin" "amd64"
  echo "    ];"

  echo "    aarch64-linux = ["
  genSrcs "linux" "arm64"
  echo "    ];"

  echo "    aarch64-darwin = ["
  genSrcs "darwin" "arm64"
  echo "    ];"

  echo "  };"
  echo "}"

} > "${SCRIPT_DIR}/extra-pulumi-packages.nix"
