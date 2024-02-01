#!/usr/bin/env bash
# shellcheck shell=bash
# Bash 3 compatible for Darwin
# Based on https://github.com/NixOS/nixpkgs/blob/master/pkgs/tools/admin/pulumi-bin/update.sh
set -euo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

declare -a plugins
plugins=(
  "auth0=3.1.0"
  "command=0.9.2"
  "kubernetes-cert-manager=0.0.5"
  # latest update
  "gcp=6.67.0"
  "kubernetes=4.5.4"
  # currently used
  "kubernetes=4.7.1"
  "random=4.14.0"
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
    local plug=${plugVers%=*}
    local version=${plugVers#*=}
    # url as defined here
    # https://github.com/pulumi/pulumi/blob/06d4dde8898b2a0de2c3c7ff8e45f97495b89d82/pkg/workspace/plugins.go#L197
    local url="https://api.pulumi.com/releases/plugins/pulumi-resource-${plug}-v${version}-${1}-${2}.tar.gz"
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
