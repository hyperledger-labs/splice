#!/usr/bin/env bash

set -eou pipefail

cd "$REPO_ROOT"

# all directories below daml/ ending in '-upgrade' are projects to be diffed
find daml/ -type d -path 'daml/*-upgrade' | \
  sed 's/-upgrade$//; s/daml\///' | \
  sort
