#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# shellcheck disable=SC2016

set -euo pipefail

rename_script="scripts/check-repo-names.sh"

function check_patterns_locally() {
  # removing NEVERMATCHES alternative causes these to never match
  local disallowed_patterns=(
    '(\b|[`_])(cn|NEVERMATCHES)(?!-test-failures)(\b|[A-Z`_])'
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
    '^[^:]+V001__create_schema\.sql:' # TODO (DACH-NY/canton-network-node#15491) avoiding changing hashes
    'AUTH0_CN_MANAGEMENT_API_CLIENT_(ID|SECRET)|"dev" => ."AUTH0_CN"' # TODO (DACH-NY/canton-network-internal#395) auth0 env names
    'ans-web-ui\.yaml:.*name: splice-app-cns-ui-auth' # TODO (DACH-NY/canton-network-internal#397) new secret
    'Headers.scala:.*"configs"'
    'Headers.scala:.*"configs-private"'
    'istio-gateway/.*gateway\.yaml:.*credentialName: cn-' # TODO (DACH-NY/canton-network-internal#396) TLS credential names in istio-gateway
    'bigquery-import.sql:.*da-cn-ci-2' # TODO (DACH-NY/canton-network-internal#362) parameterized project
    'GcpConfig\.scala:' # cluster-specific
    '/da-cn-shared/cn-images|GOOGLE_CLOUD_PROJECT=da-cn-shared|"KMS_PROJECT_ID" -> "da-cn-shared"' # gcp
    '/cn-release-bundles' # docs route
    'cn-(http|apps|public-http)-gateway' # helm gateway templates
    'SpliceTests\.scala.*getMeterProvider\.get."cn_tests"' # test metrics
    '^[^:]+package-lock\.json:.*"integrity"' # appears in hashes
    'Preflight.*Test.*\.scala:.*s"https://cns' # hostnames in preflights
    'Test.*\.scala:.*da-cn-splice' # GCP project we use for KMS keys used in integration tests
    'cluster/images/splice-test-temp-runner-hook/index\.js' # gha-runner-hook copied over
    'apps/app/src/test/resources/dumps/.*-identity-dump\.json' # encoded snapshots can randomly contain 'cn'
    'apps/app/src/test/resources/.*\.sql' # sql queries must use the names in the DB
    'token-standard/CHANGELOG.md'
    'Canton Network Token Standard'
    'stop-frontends\.sh'
    'start-frontends\.sh'
    'scripts'
    'build-tools'
    'support'
    'start-canton\.sh'
    'docs/'
    'cluster/pulumi/'
    'cluster/deployment'
    'cluster/stacks'
    'cluster/images/LICENSE'
    'expected.json'
    'README.md'
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

check_patterns_locally
