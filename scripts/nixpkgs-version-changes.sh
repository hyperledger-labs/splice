#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Show package version changes in the nix dev shell between two flake refs,
# for the current system.
# usage: nixpkgs-version-changes.sh [OLD_GIT_REV] [NEW_FLAKE_REF]
#   defaults: OLD = merge-base of origin/main and HEAD,
#             NEW = this checkout's working tree (path:./nix)

set -euo pipefail

repo=$(git rev-parse --show-toplevel)
old="git+file://$repo?rev=$(git rev-parse "${1:-$(git merge-base origin/main HEAD)}")&dir=nix"
new="${2:-path:$repo/nix}"

# shellcheck disable=SC2016 # ${...} below is Nix interpolation, not shell
exec nix eval --impure --raw --expr '
let
  sys = builtins.currentSystem;
  flake = ref: builtins.getFlake ref;
  # line up pythonX.Y-foo across Python version bumps
  norm = n: let m = builtins.match "python3\\.[0-9]+-(.*)" n; in if m == null then n else "python3-" + builtins.head m;
  vers = ref: let s = (flake ref).devShells.${sys}.default; in
    builtins.listToAttrs (map (p: let d = builtins.parseDrvName p.name; in { name = norm d.name; value = d.version; })
      (s.buildInputs ++ s.nativeBuildInputs ++ s.propagatedBuildInputs));
  rev = ref: (flake ref).inputs.nixpkgs.rev;
  a = vers "'"$old"'"; b = vers "'"$new"'";
  changed = builtins.filter (n: (a.${n} or "-") != (b.${n} or "-")) (builtins.attrNames (a // b));
in
  "system ${sys}\nnixpkgs ${rev "'"$old"'"} -> ${rev "'"$new"'"}\n"
  + builtins.concatStringsSep "" (map (n: "${n}: ${a.${n} or "-"} -> ${b.${n} or "-"}\n") changed)'
