#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <new-canton-version>"
    exit 1
fi

NEW_VERSION="$1"

function set_value() {
  local key="$1"
  local value="$2"

  jq --arg key "$key" --arg value "$value" '.[ $key ] = $value' nix/canton-sources.json > nix/canton-sources.tmp.json
  mv nix/canton-sources.tmp.json nix/canton-sources.json
}

set_value version "$NEW_VERSION"

_info "Fetching enterprise tar.gz hash..."
enterprise_sha256=$(nix store prefetch-file --json --hash-type sha256 "https://digitalasset.jfrog.io/artifactory/canton-enterprise/canton-enterprise-${NEW_VERSION}.tar.gz" | jq -r '.hash')
# Apparently, nix256 is a thing... While nix seems to accept both, when you have the wrong one,
# nix shows the nix256-formatted one, so we use that one too
# (also for backward compatibility with the format we used before this script)
enterprise_nix256="sha256:"$(nix hash convert --to nix32 "$enterprise_sha256")
set_value enterprise_sha256 "$enterprise_nix256"

_info "Fetching open source tar.gz hash..."
oss_sha256=$(nix store prefetch-file --json --hash-type sha256 "https://www.canton.io/releases/canton-open-source-${NEW_VERSION}.tar.gz" | jq -r '.hash')
oss_nix256="sha256:"$(nix hash convert --to nix32 "$oss_sha256")
set_value oss_sha256 "$oss_nix256"

for img in base participant mediator sequencer; do
  _info "Fetching image sha256 for canton-$img..."
  sha=$(skopeo inspect --override-os linux --override-arch amd64 "docker://europe-docker.pkg.dev/da-images/public-all/docker/canton-$img:${NEW_VERSION}" --format '{{.Digest}}')
  set_value "canton_${img}_image_sha256" "$sha"
done
