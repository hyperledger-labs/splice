#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0



# Copied & adapted from the `canton-network-node` repo

# A script to install the snapshot releases of the Daml SDK that we use in this repo.

set -eou pipefail
cd "$(dirname "$0")"

# Ensure we have a recent daml assistant installed for bootstrapping the install process
# Older daml assistants fail to do an inplace install
if [[ "$*" != *"--no-bootstrap"* ]]; then
    echo "== Installing bootstrapping release of Daml SDK (skip with --no-bootstrap)"
    export -n DAML_SDK_VERSION # This is set in .envrc.vars, but we need to ignore here for bootstrap
    curl -sSL https://get.daml.com/ | sh
else
    echo "== Skipping installation of a bootstrapping release of the Daml SDK assuming a recent version (>= 2.8.x) is installed"
fi

# Get the Daml version is present in the JSON file
yaml_file="../sdk-version.yaml"
sdk_version=$(yq -re '.sdk-version // ""' "$yaml_file")
if [ -z "$sdk_version" ]; then
    echo "Error: 'sdk-version' not found in $yaml_file."
    exit 1
fi

sdk_snapshot=$(yq -re '.sdk-snapshot // ""' "$yaml_file")
if [ -z "$sdk_snapshot" ]; then
    echo "Error: 'sdk-snapshot' not found in $yaml_file."
    exit 1
fi

# Determine the operating system
if [[ "$(uname -s)" == "Linux" ]]; then
    arch=$(uname -m)
    os="linux-$arch"
elif [[ "$(uname -s)" == "Darwin" ]]; then
    os="macos-x86_64"
else
    echo "OS not supported for development in this repo."
fi

url="https://github.com/digital-asset/daml/releases/download/v$sdk_snapshot/daml-sdk-$sdk_version-$os.tar.gz"


# Download tarball at URL to a temporary directory
tmp_dir=$(mktemp -d)
tarball="$tmp_dir/daml-sdk.tar.gz"
echo "== Downloading Daml SDK (version $sdk_version) from snapshot $sdk_snapshot for $os"
echo "from: $url"
echo "to: $tarball"
curl --fail -L "$url" -o "$tarball"


# Install the Daml SDK
echo "== Installing Daml SDK version: $sdk_version"
export PATH="$HOME/.daml/bin:$PATH"
daml install --install-assistant yes --install-with-internal-version yes "$tarball"


# Cleanup temp dir
rm -rf "$tmp_dir"

echo "== Installation complete."

