#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# shellcheck disable=SC2016

set -euo pipefail

SPLICE_ROOT=$( git rev-parse --show-toplevel )
copy_script="scripts/copy-to-splice.sh"
rename_script="scripts/check-repo-names.sh"
if [[ -f "$SPLICE_ROOT/$copy_script" ]]; then
  in_copy_src=yes
else
  in_copy_src=no
fi

function check_patterns_locally() {
  # removing NEVERMATCHES alternative causes these to never match
  local disallowed_patterns=(
    '(\b|[`_])(cn|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[a-z`_])(CN|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[`_])(GS|NEVERMATCHES)(\b|[`_])' # the GS abbreviation is problematic, write out Global Synchronizer
    '(\b|[`_])(cns|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[a-z`_])(CNS|NEVERMATCHES)(\b|[A-Z`_])'
    '(?i)canton network'
    '(?i)canton coin'
  )
  # exceptions are searched against grep lines, which follow the format
  # path/to/file:linenumber:line-contents
  # so that metadata may be incorporated into any of exceptions
  local exceptions=(
    '(\b|[`_])cn-docs'
    '@cn-load-tester\.com'
    '^[^:]+V001__create_schema\.sql:' # TODO (#15491) avoiding changing hashes
    'AUTH0_CN_MANAGEMENT_API_CLIENT_(ID|SECRET)|"dev" => ."AUTH0_CN"' # TODO (#15747) auth0 env names
    'ans-web-ui\.yaml:.*name: splice-app-cns-ui-auth' # TODO (#15741) new secret
    'Headers.scala:.*"configs"'
    'Headers.scala:.*"configs-private"'
    'istio-gateway/.*gateway\.yaml:.*credentialName: cn-' # TODO (#15745) TLS credential names in istio-gateway
    'GcpConfig\.scala:' # cluster-specific
    '/da-cn-shared/cn-images|GOOGLE_CLOUD_PROJECT=da-cn-shared|"KMS_PROJECT_ID" -> "da-cn-shared"' # gcp
    '/cn-release-bundles' # docs route
    'cn-(http|apps|public-http)-gateway' # helm gateway templates
    'SpliceTests\.scala.*getMeterProvider\.get."cn_tests"' # test metrics
    '^[^:]+package-lock\.json:.*"integrity"' # appears in hashes
    'Preflight.*Test.*\.scala:.*s"https://cns' # hostnames in preflights
    'cluster/images/splice-test-temp-runner-hook/index.js' # gha-runner-hook copied over
    'apps/app/src/test/resources/dumps/.*-identity-dump.json' # encoded snapshots can randomly contain 'cn'
  )

  local exception exceptions_args=()
  for exception in "${exceptions[@]}"; do
    exceptions_args+=("--regexp=$exception")
  done

  local pattern matches fail=0
  for pattern in "${disallowed_patterns[@]}"; do
    local sensitivity
    case x"$pattern" in
      "x(?i)"*) sensitivity=insensitive;;
      *) sensitivity=sensitive;;
    esac
    echo "Checking for occurrences of '$pattern' (case $sensitivity)"
    set +e
    matches="$(rg --no-require-git --line-number --engine=pcre2 --regexp="$pattern" \
                  --glob='!'"$rename_script" --glob='!/canton/**/*' \
                | rg --invert-match --engine=pcre2 "${exceptions_args[@]}")"
    set -e
    if [[ -n $matches ]]; then
      echo "$pattern occurs in code, please remedy"
      echo "$matches"
      fail=1
    else
      echo "no name clashes detected with $pattern"
    fi
  done

  if [[ $fail -ne 0 ]]; then
    exit $fail
  fi
}

function setup_temp_splice() {
  local src="$1" tempsplice
  tempsplice="$(mktemp -d)"
  cd "$src"
  local script_prefix
  case $in_copy_src in
    yes) script_prefix=;;
    no) script_prefix='direnv exec .';;
  esac
  $script_prefix "$copy_script" "$tempsplice"
  cd "$tempsplice"
}

function check_patterns() {
  local optstring
  case "$in_copy_src" in
    yes)
      optstring='h'
      setup_temp_splice "$SPLICE_ROOT";;
    no) optstring='hs:';;
  esac

  while getopts "$optstring" arg; do
    case "$arg" in
      h)
        echo '  Options: [-s SPLICE_REPO]
    -s: Run copy-to-splice from SPLICE_REPO first, and scan the result' 1>&2
        exit 0;;
      s)
        if [[ ! -d $OPTARG ]]; then
          echo "-s requires a splice repo directory" 1>&2
          exit 1
        fi
        setup_temp_splice "$OPTARG";;
      :|?) exit 1;;
    esac
  done
  check_patterns_locally
}

check_patterns "$@"
