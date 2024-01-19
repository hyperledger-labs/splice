#!/bin/bash

set -euo pipefail

## Check for 'fetch' invocations in Daml code

# Note, there might be the alternative to enlist the help of `damlc lint` (built on hlint) to check for
# these invocations of 'fetch' using a custom rule: https://github.com/ndmitchell/hlint/blob/master/README.md#adding-hints
#
# However the grep-based code below is nice and simple, which is why we're using it for now.

ignored_files=(
  'daml/cn-util/daml/CN/Util.daml'
  'canton/')

command=('git' 'grep' '-P' '(?<!-- )fetch\b' '--' '*.daml')
for ignored_file in "${ignored_files[@]}"; do
  command+=(":!$ignored_file")
done

if "${command[@]}" &> /dev/null ; then
  echo "ERROR: found naked 'fetch' invocations:"
  echo ""
  "${command[@]}"
  echo ""
  echo "Please replace them with one of the alternatives in CN.ChoiceUtil:"
  echo "fetchAndArchive, fetchReferenceData, or if really required, fetchButArchiveLater"
  exit 1
fi

echo "No Daml warts found."
