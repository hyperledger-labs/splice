#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

## Check for 'fetch' invocations in Daml code

# Note, there might be the alternative to enlist the help of `damlc lint` (built on hlint) to check for
# these invocations of 'fetch' using a custom rule: https://github.com/ndmitchell/hlint/blob/master/README.md#adding-hints
#
# However the grep-based code below is nice and simple, which is why we're using it for now.

ignored_files=(
  'daml/splice-util/daml/Splice/Util.daml'
  'token-standard/examples/splice-token-test-trading-app/daml/Splice/Testing/Apps/TradingApp.daml'
  'daml/splice-util-featured-app-proxies/daml/Splice/Util/FeaturedApp/DelegateProxy.daml'
  'daml/splice-util-featured-app-proxies/daml/Splice/Util/FeaturedApp/WalletUserProxy.daml'
  'canton/')

command=('git' 'grep' '-n' -E '(exercise.*_Fetch|fetch|archive)\b' '--' '*.daml')
echo "${command[@]}"
for ignored_file in "${ignored_files[@]}"; do
  command+=(":!$ignored_file")
done

## Ignore matches of comment lines
ignore_comments=('grep' '-v' '-E' '^.*\.daml:[0-9]*:\s*--')


if "${command[@]}" | "${ignore_comments[@]}" &> /dev/null ; then
  echo "ERROR: found naked 'fetch' or 'archive' invocations:"
  echo ""
  "${command[@]}" | "${ignore_comments[@]}"
  echo ""
  echo "Please replace them with one of the alternatives in Splice.ChoiceUtil:"
  echo "- for fetch: fetchAndArchive, fetchReferenceData, or if really required, fetchButArchiveLater"
  echo "- for archive: fetchAndArchive, or if really required, potentiallyUnsafeArchive"
  exit 1
fi

echo "No Daml warts found."
