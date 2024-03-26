#!/usr/bin/env bash

SUBCOMMAND_NAME="$1"
shift

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

SCRIPTNAME=${0##*/}

cd "$REPO_ROOT"

### Env var flag defaulting

SKIP_CN_CLEAN=${SKIP_CN_CLEAN:-0}


### Utility functions ###

rename_script="scripts/rename.sh"

function modified_files() {
  git status --porcelain | grep -v "^...$rename_script"
}

function run_and_commit() {
  local description=$1
  local cmd=$2

  # Echo the description
  _info "== $description =="

  # Run the command
  eval "$cmd"

  # Check if any files were modified
  if modified_files; then
    # Add all modified files to the staging area, excluding this script.
    git add -A
    git reset -- "$rename_script"

    # Commit the changes
    git commit --no-verify -m "$description" -m "CMD: $cmd"
  else
    echo "Skipping commit, as there were no changes."
  fi
  echo ""
}

function run_and_store_output() {
  local description=$1
  local cmd=$2

  # Run the command and capture its output
  local output
  output=$(eval "echo 'OUPTUT: BEGIN' && $cmd && echo 'OUTPUT: END'")

  # Store in an empty commit
  git commit --no-verify --allow-empty -m "$description" -m "CMD: $cmd" -m "$output"
}

function assert_clean_working_dir() {
  if modified_files; then
    _error "Error: there are uncommitted modifications to files other than $rename_script. Please commit or stash them before running this script."
  fi
}

function assert_no_usage() {
  local pattern=$1
  local args=('-E' "$pattern" '--' ":!$rename_script" ":!canton/")

  # Check if the renaming would cause any name clashes
  if git grep "${args[@]}"; then
    _error "Error: Renaming would cause name clashes with '$pattern'."
  else
    _info "No name clashes detected with '$pattern'."
  fi
}

function assert_no_canton_usage() {
  local pattern=$1
  local args=('-E' "$pattern" '--' ':canton/')

  # Check if the renaming would cause any name clashes
  if git grep "${args[@]}"; then
    _error "Error: Canton fork also uses '$pattern'. Manually rename these occurrences first."
  else
    _info "Canton fork does not use '$pattern'."
  fi
}

#### Staged Renaming ####

function exclude_all() {
  local patterns=("$@")
  local args=()

  for pattern in "${patterns[@]}"; do
    args+=("-e '$pattern'")
  done

  echo "${args[@]}"
}

crypto_files=(
  '*.pem'
  '*.key'
  '*key.json'
  '*.sha256'
  '**/Pulumi.*.yaml'
  '**/*participant*dump.json'
)

lock_files=(
  '*.lock'
  'nix/**'
  '*lock.json'
  '**/expected*.json'
)

cluster_files=(
  'cluster/**'
)

docs_files=(
  '*.md'
  '*.txt'
  '*.rst'
)

special_files=(
  '*.ignore.txt'
  '.envrc.*'
  'scripts/**'
  'build.sbt'
  'build-tools/**'
  'project/**'
  '*.sh'
  '.circleci/**'
)

frontend_files=(
  '**/frontend/**'
)

api_files=(
  '**/openapi/**'
  '*.proto'
)

canton_files=(
  'canton/**'
)

protected_files=(
  "$rename_script"
  '**/release_notes.rst'
  '**/auth0.ts'
)

NO_PROTECTED=$(exclude_all "${protected_files[@]}")
NO_LOCKS=$(exclude_all "${lock_files[@]}")
NO_CANTON=$(exclude_all "${canton_files[@]}")

NO_CRYPTO=$(exclude_all "${crypto_files[@]}")
NO_CLUSTER=$(exclude_all "${cluster_files[@]}")
NO_DOCS=$(exclude_all "${docs_files[@]}")
NO_SPECIAL=$(exclude_all "${special_files[@]}")
NO_FRONTEND=$(exclude_all "${frontend_files[@]}")
NO_API=$(exclude_all "${api_files[@]}")


function rename() {
  local description=$1
  local pattern=$2
  local extra_includes=$3
  local extra_excludes=$4

  # Build the excludes in reverse order, exploiting the idempotency of the renames when applying them
  local SPECIAL="$extra_excludes $NO_PROTECTED $NO_LOCKS $NO_CANTON"
  local DOCS="$NO_SPECIAL $SPECIAL"
  local CRYPTO="$NO_DOCS $DOCS"
  local CLUSTER="$NO_CRYPTO $CRYPTO"
  local FRONTEND="$NO_CLUSTER $CLUSTER"
  local API="$NO_FRONTEND $FRONTEND"
  local APP="$NO_API $API"

  # Daml files are easier to specify as a separate group
  local DAML="$SPECIAL"

  # We are not adjusting lock files, as they are regenerated in the cleanup step
  if [ -z "$extra_includes" ]; then
    run_and_commit "renaming $description: daml files"      "gsr -i 'daml/**' $DAML      -f $pattern"
  fi
  run_and_commit "renaming $description: app files"       "gsr $extra_includes $APP       -f $pattern"
  run_and_commit "renaming $description: api files"       "gsr $extra_includes $API       -f $pattern"
  run_and_commit "renaming $description: frontend files"  "gsr $extra_includes $FRONTEND  -f $pattern"
  run_and_commit "renaming $description: cluster files"   "gsr $extra_includes $CLUSTER   -f $pattern"
  run_and_commit "renaming $description: crypto files"    "gsr $extra_includes $CRYPTO    -f $pattern"
  run_and_commit "renaming $description: doc files"       "gsr $extra_includes $DOCS      -f $pattern"
  run_and_commit "renaming $description: special files"   "gsr $extra_includes $SPECIAL   -f $pattern"

}

function commit_occurrences() {
  local pattern=$1

  local cmd="(GIT_PAGER=cat git grep -P '$pattern' -- ':!$rename_script' ':!canton/**' || true) && (GIT_PAGER=cat git ls-files | grep -P '$pattern' || true)"

  _info "Checking and storing left-over occurrences of '$pattern'"
  eval "$cmd"
  run_and_store_output "left-over occurrences of '$pattern'" "$cmd"
}


################################
### Commands
################################

# Note: command infra copied verbatim from 'cncluster'

## Subcommand dictionary
##
##    This is a map from subcommand name to help text. The definition of the
##    sumcommand's functionality itself is in a shell function named
##    cluster_${subcommand_name}. The subcommand must be defined in the
##    whitelist dictionary to be invoked.

declare -A subcommand_whitelist


subcommand_whitelist[internal_cleanup]='Format files and update lock files'

function subcmd_internal_cleanup() {

  _info "Creating cleanup changes"

  if [[ $SKIP_CN_CLEAN != 1 ]]; then
    sbt --client cn-clean
  fi

  run_and_commit "cleanup: regenerate .daml and .ts lock files" "sbt --client 'Test/compile; bundle; damlDarsLockFileUpdate'"
  run_and_commit "cleanup: format .scala files" "sbt --client format"
  run_and_commit "cleanup: format .ts files" "sbt --client npmFix"
  run_and_commit "cleanup: regenerate pulumi expected.json files" "make cluster/pulumi/update-expected"

  git commit --no-verify --allow-empty -m"Mark this renaming change as a [breaking] change."
}

### SVC


subcommand_whitelist[internal_svc_dso_rename]='Internal - rename: svc to dso'

function subcmd_internal_svc_dso_rename() {
  assert_clean_working_dir

  assert_no_canton_usage 'svc|Svc|SVC'

  local k8s_files=(
    # svc stands for k8s service in these - requires manual verification
    'cluster/**/templates/*.yaml'
    '**/values-template.yaml'
    # There is an 'svc' string in one of the hashes :'(
    '**/Pulumi.*.yaml'
  )

  local NO_K8S
  NO_K8S="$(exclude_all "${k8s_files[@]}")"

  # The use of 'svc.` mostly occurs as part of DNS names and k8s names
  # We rename these usages separately.
  rename "svc to dso (for occurences other than 'svc.')" \
    "'(?<!(clouddns-dns01-solver-))(?<!(kubectl get ))(?<!(kubectl edit ))(\b|(?<=[_]))svc(?![.])(\b|(?=([A-Z_]|rules|bootstrap)))///dso'" \
    "" \
    "$NO_K8S"

  rename "svc.ts to dso.ts" \
    "'svc\.ts///dso.ts'" \
    "" \
    "$NO_K8S"

  # We ignore the $ suffix as that's used for string interpolation to build URLs in .scala files
  rename "svc. to dso. (in *.scala files and selected typescript)" \
    "'\bsvc\.(?![\\\$])///dso.'" \
    "-i '*.scala' -i cluster/pulumi/canton-network/src/dso.ts -i cluster/pulumi/canton-network/src/installCluster.ts" \
    ""

  # The pg|gw|Gw ignores are for k8s service names
  rename "Svc to Dso" \
    "'(?<!(pg|gw|Gw))(\b|(?<=([a-z_])))Svc(\b|(?=([A-Z_])))///Dso'" \
    "" \
    ""
  rename "SVC to DSO" \
    "'\bSVC(\b|(?=([_]|Rules)))///DSO'" \
    "" \
    ""
}


subcommand_whitelist[internal_svc_dso_occs]='Internal - Check and store occurrences for: svc'

function subcmd_internal_svc_dso_occs() {
  assert_clean_working_dir

  # Too many occurrences of k8s service `svc.cluster` to list here
  commit_occurrences 'svc(?!\.cluster)'
  commit_occurrences "Svc"
  commit_occurrences "SVC"
}


subcommand_whitelist[svc_dso]='Rename: svc to dso (run this after a merge where you resolve conflicts to your version)'

function subcmd_svc_dso() {
  subcmd_internal_svc_dso_rename
  subcmd_internal_cleanup
  subcmd_internal_svc_dso_occs
}


### Coin

subcommand_whitelist[internal_coin_amulet_rename]='Internal - Rename: coin to amulet'

function subcmd_internal_coin_amulet_rename() {
  assert_clean_working_dir

  assert_no_canton_usage 'amulet|Amulet'
  assert_no_canton_usage '\bcoin\b|\bCoin\b'

  # Fix two accidental typos that are in our codebase
  rename "coinCointractId to coinContractId" \
    "'\bcoinCointractId\b///coinContractId'" \
    "" \
    ""
  rename "coints to amulets" \
    "'\bcoints\b///amulets'" \
    "" \
    ""

  # We do not change the rst files on our docs, as they do not contain URLs that need changing (manully verified)
  local IGNORE_DOCS_RST="-e '**docs/**/*.rst'"

  # Note: not using word-boundary check for specific enough suffixes
  # We keep '[Cc]anton coin' mentions as they are mostly in user facing docs, which we need to adjust
  # manually once the time is ripe.
  rename "coin to amulet" \
    "'(?<!([Cc]anton ))(\b|(?<=[_-]))coin(\b|(?=([A-Z0-9_-]|rules|operation|s\b|s[A-Z0-9_]|config\b|price\b)))///amulet'" \
    "" \
    "$IGNORE_DOCS_RST"
  rename "createdcoin to createdamulet" \
    "'\bcreatedcoin\b///createdamulet'" \
    "" \
    "$IGNORE_DOCS_RST"

  # We keep '[Cc]anton Coin' mentions as they are mostly in user facing docs, which we need to adjust
  # manually once the time is ripe.
  rename "Coin to Amulet" \
    "'(?<!([Cc]anton ))(\b|(?<=([a-z0-9_])))Coin(\b|(?=([A-Z0-9_]|s[A-Z0-9_]|s\b)))///Amulet'" \
    "" \
    ""

  # Ensure our Daml code does not mention Canton Coin
  local canton_coin_pattern="'\b[cC]anton [cC]oin\b///amulet'"
  run_and_commit "rename Canton Coin to amulet: daml files only" "gsr -i 'daml/**' -f $canton_coin_pattern"
}


subcommand_whitelist[internal_coin_amulet_occs]='Internal - Check and store occurrences for: coin'

function subcmd_internal_coin_amulet_occs() {
  assert_clean_working_dir

  commit_occurrences '(?<![Cc]anton )coin'
  commit_occurrences '(?<![Cc]anton )Coin'
  commit_occurrences "COIN"
}

subcommand_whitelist[coin_amulet]='Rename: coin to amulet (run this after a merge where you resolve conflicts to your version)'

function subcmd_coin_amulet() {
  subcmd_internal_coin_amulet_rename
  subcmd_internal_cleanup
  subcmd_internal_coin_amulet_occs
}


### CNS

# TODO(#11111): complete this part of the script

subcommand_whitelist[internal_cns_amulet]='Rename: cns to ans'

function subcmd_internal_cns_ans() {
  assert_clean_working_dir

  assert_no_usage 'ans|Ans|ANS'
  assert_no_canton_usage 'ans|Ans|ANS'

  # Rename cns and CNS
  rename "cns to ans" \
    "'\bcns\b///ans'" \
    "" \
    ""
  rename "Cns to Ans" \
    "'\bCns\b///Ans'" \
    "" \
    ""
  rename "CNS to ANS" \
    "'\bCNS\b///ANS'" \
    "" \
    ""
}


subcommand_whitelist[internal_cns_occs]='Check and store occurrences for: cns'

function subcmd_internal_cns_occs() {
  assert_clean_working_dir

  commit_occurrences "cns"
  commit_occurrences "Cns"
  commit_occurrences "CNS"
  commit_occurrences "Canton Name"

  # TODO(#11111): check for Canton Name
}

### CCApp and CCUser

subcommand_whitelist[cc_app_and_user]='Rename: CCApp to AmuletApp and CCUser to AmuletUser'

function subcmd_cc_app_and_user() {
  assert_clean_working_dir

  # Rename cns and CNS
  rename "CCApp to AmuletApp" \
    "'\bCCApp\b///AmuletApp'" \
    "" \
    ""
  rename "CCUser to AmuletUser" \
    "'\bCCUser\b///AmuletUser'" \
    "" \
    ""
}

subcommand_whitelist[cc_module_splice]='Rename: CC Module prefix to Splice'
function subcmd_cc_module_splice() {
  assert_clean_working_dir

  rename "long-term state of CC -> long-term state of Amulet"  \
    "'long-term state of CC///long-term state of Splice'" \
    "" \
    ""

  rename "Bob 10CC -> Bob 10Amulet"  \
    "'Bob 10CC///Bob 10Amulet'" \
    "" \
    ""

  rename "Alice 10CC -> Alice 10Amulet"  \
    "'Alice 10CC///Alice 10Amulet'" \
    "" \
    ""

  rename "CC/Round.daml -> Splice/Round.daml"  \
    "'CC/Round.daml|||Splice/Round.daml'" \
    "" \
    "-s '|||'"

  rename "lib/CC/Round -> lib/Splice/Round"  \
    "'lib/CC/Round|||lib/Splice/Round'" \
    "" \
    "-s '|||'"

  rename "CC/Round -> Amulet/Round"  \
    "'CC/Round|||Amulet/Round'" \
    "" \
    "-s '|||'"

  rename "CC/USD -> Amulet/USD"  \
    "'CC/USD|||Amulet/USD'" \
    "-e apps/app/src/test/scala/com/daml/network/integration/tests/WalletSubscriptionsFrontendIntegrationTest.scala" \
    "-s '|||'"

  rename "(defaultHoldingFee|transferAmount|amount|total|transferConfig|transferAmountUSDin)CC -> *Amulet"  \
    "'(defaultHoldingFee|transferAmount|amount|total|transferConfig|transferAmountUSDin|expectedAmount)CC///\1Amulet'" \
    "" \
    ""

  rename "offered CC -> offered Amulet"  \
    "'offered CC///offered Amulet'" \
    "" \
    ""

  rename "CC. to Splice."  \
    "'CC\.///Splice.'" \
    "" \
    ""

  rename "CC/ to Splice/"  \
    "'CC/|||Splice/'" \
    "-e apps/app/src/test/scala/com/daml/network/integration/tests/WalletSubscriptionsFrontendIntegrationTest.scala" \
    "-s '|||'"

  rename "codegen.java.cc to codegen.java.splice"  \
    "'codegen\.java\.(\{?)cc///codegen.java.\1splice'" \
    "" \
    ""

  rename "cc. to splice. in scala code"  \
    "'([^a])cc\.///\1splice.'" \
    "" \
    ""
}


subcommand_whitelist[cn_module_splice]='Rename: CN Module prefix to Splice'
function subcmd_cn_module_splice() {
  assert_clean_working_dir

  rename "CN. to Splice."  \
    "'CN\.///Splice.'" \
    "" \
    ""

  rename "CN/ to Splice/"  \
    "'CN/|||Splice/'" \
    "" \
    "-s '|||'"

  rename "codegen.java.cc to codegen.java.splice"  \
    "'codegen\.java\.cn///codegen.java.splice'" \
    "" \
    ""

  rename "codegen.java.cc to codegen.java.splice"  \
    "'codegen\.java\.\{splice, cn\}///codegen.java.splice'" \
    "" \
    ""

  rename "cn. to splice."  \
    "'cn\.///splice.'" \
    "" \
    ""
}

### Splice packages

# TODO(#11111): complete this part of the script

function rename_daml_package() {
  local old=$1
  local new=$2

  assert_clean_working_dir

  local rename_pattern="'(?<![-])\b$old\b(?![-][a-zA-Z])///$new'"
  run_and_commit "rename $old to $new in daml/ directory" "gsr -i 'daml/**/*.yaml' -f $rename_pattern"

  # More conservative renames outside of the daml/ directory
  rename "daml/$old" \
    "'(?<=daml/)$old\b(?![-])///$new'" \
    "" \
    ""
  rename "daml.js/$old" \
    "'(?<=daml.js/)$old\b(?![-][a-zA-Z])///$new'" \
    "" \
    ""
  rename "$old .dar files" \
    "'(?<![-])\b$old(?=-[a-zA-Z0-9.]+[.]dar)///$new'" \
    "" \
    ""
  rename "$old-daml" \
    "'(?<![-])\b$old(?=-daml)///$new'" \
    "" \
    ""
}

subcommand_whitelist[cn_util_package]='Rename: Daml package cn-util to splice-util'
function subcmd_cn_util_package() {
  rename_daml_package 'cn-util' 'splice-util'
}

subcommand_whitelist[wallet_package]='Rename: Daml package wallet to splice-wallet'
function subcmd_wallet_package() {
  rename_daml_package 'wallet' 'splice-wallet'
  rename_daml_package 'wallet-test' 'splice-wallet-test'

  # subcmd_internal_cleanup

  # commit_occurrences "(?<![-])\bwallet\b(?![-])"
}

################################
### Main
################################

function subcmd_help() {
    _info "Usage: $SCRIPTNAME {subcommand} [options...]"

    _info "Valid subcommands: "
    readarray -t sorted < <(printf '%s\n' "${!subcommand_whitelist[@]}" | sort)

    for ii in "${sorted[@]}";
    do
        if [[ "$ii" != ci_* && "$ii" != _* ]]; then
            printf '%s\t%s\n' "${ii}" "${subcommand_whitelist[${ii}]}"
        fi
    done | column -ts $'\t'
}

if [ -z "${SUBCOMMAND_NAME-}" ]; then
    subcmd_help

    _error  "Missing subcommand"
fi


if [ ! ${subcommand_whitelist[${SUBCOMMAND_NAME}]+_} ]; then
    subcmd_help

    _error  "Unknown subcommand: ${SUBCOMMAND_NAME}"
fi

"subcmd_${SUBCOMMAND_NAME}" "$@"
