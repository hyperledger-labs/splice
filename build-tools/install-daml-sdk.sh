#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# A script to install the snapshot releases of the Daml SDK that we use in this repo.



set -eou pipefail
cd "$(dirname "$0")"

# Ensure we have a recent daml assistant installed for bootstrapping the install process
# Older daml assistants fail to do an inplace install
if [[ "$*" != *"--no-bootstrap"* ]]; then
    echo "== Installing bootstrapping release of Daml SDK (skip with --no-bootstrap)"
    curl -sSL https://get.daml.com/ | sh
else
    echo "== Skipping installation of a bootstrapping release of the Daml SDK assuming a recent version (>= 2.8.x) is installed"
fi

# Get the Daml version is present in the YAML file
yaml_file="$SPLICE_ROOT/daml.yaml"
daml_version=$(yq e '.sdk-version' "$yaml_file")
if [ -z "$daml_version" ]; then
    echo "Error: Daml version not found in $yaml_file."
    exit 1
fi

# Determine the operating system
if [[ "$(uname -s)" == "Linux" ]]; then
    os="linux-intel"
elif [[ "$(uname -s)" == "Darwin" ]]; then
    os="macos"
else
    echo "OS not supported for development in this repo."
fi

url="https://digitalasset.jfrog.io/artifactory/assembly/daml/$daml_version/daml-sdk-$daml_version-$os-ee.tar.gz"

# Download tarball at URL to a temporary directory
tmp_dir=$(mktemp -d)
tarball="$tmp_dir/daml-sdk.tar.gz"
echo "== Downloading Daml SDK $daml_version for $os"
echo "from: $url"
echo "to: $tarball"
curl -u "$ARTIFACTORY_USER:$ARTIFACTORY_PASSWORD" -L "$url" -o "$tarball"


# Install the Daml SDK
echo "== Installing Daml SDK version: $daml_version"
daml install --install-assistant yes --install-with-internal-version yes "$tarball"


# Cleanup temp dir
rm -rf "$tmp_dir"

echo "== Installation complete."
