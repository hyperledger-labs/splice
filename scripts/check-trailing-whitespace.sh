#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Check for trailing whitespace in all files tracked by git, excluding the /canton directory and known binary files
ignored_files=(
  '*.tgz'
  '*.png'
  '*.diff'
  '*.ico'
  '*.woff'
  'canton/*')

# Build the command
command=('git' 'grep' '--no-pager' '-l' '-E' '\s+$' '--')
for ignored_file in "${ignored_files[@]}"; do
  command+=(":!$ignored_file")
done

# Run and fix if trailing whitespace is found
if "${command[@]}" &> /dev/null ; then
  echo "ERROR: found and removed trailing whitespace in"
  "${command[@]}"
  "${command[@]}" | xargs -I {} sed -i 's/\s\+$//' {}
  echo "Please commit the changes."
  exit 1
else
  echo "No trailing whitespace found."
fi
