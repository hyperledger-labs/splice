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


function simple_rename() {
  local pattern=$1
  local extra_args=${2:-""}

  run_and_commit "rename: $pattern" "gsr -f $extra_args $NO_PROTECTED $NO_LOCKS $NO_CANTON '$pattern'"
}

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
    run_and_commit_rename "$description: daml files"    "$pattern" "-i 'daml/**'" "$DAML"

  fi
  run_and_commit_rename "$description: app files"       "$pattern" "$extra_includes" "$APP"
  run_and_commit_rename "$description: api files"       "$pattern" "$extra_includes" "$API"
  run_and_commit_rename "$description: frontend files"  "$pattern" "$extra_includes" "$FRONTEND"
  run_and_commit_rename "$description: cluster files"   "$pattern" "$extra_includes" "$CLUSTER"
  run_and_commit_rename "$description: crypto files"    "$pattern" "$extra_includes" "$CRYPTO"
  run_and_commit_rename "$description: doc files"       "$pattern" "$extra_includes" "$DOCS"
  run_and_commit_rename "$description: special files"   "$pattern" "$extra_includes" "$SPECIAL"
}

function run_and_commit_rename() {
  local description=$1
  local pattern=$2
  shift 2
  local gsr_args=("$@")

  run_and_commit "renaming: $description" "gsr ${gsr_args[*]} -f $pattern"
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


subcommand_whitelist[cleanup_full]='Cleanup: run cn-clean, format all files, and update all lock files'

function subcmd_cleanup_full() {

  if [[ $SKIP_CN_CLEAN != 1 ]]; then
    sbt --client cn-clean
  fi

  subcmd_cleanup_light

  run_and_commit "cleanup: regenerate pulumi expected.json files" "make cluster/pulumi/update-expected"
}

subcommand_whitelist[cleanup_light]='Cleanup: format all files and update non-cluster lock files'

function subcmd_cleanup_light() {

  _info "Creating cleanup changes"

  run_and_commit "cleanup: regenerate .daml and .ts lock files" "sbt --client 'Test/compile; bundle; damlDarsLockFileUpdate'"
  run_and_commit "cleanup: format .scala files" "sbt --client format"
  run_and_commit "cleanup: format .ts files" "sbt --client npmFix"

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

subcommand_whitelist[internal_cns_ans]='Rename: cns to ans'

function subcmd_internal_cns_ans() {
  assert_clean_working_dir

  assert_no_usage 'ans|Ans|ANS'
  assert_no_canton_usage 'ans|Ans|ANS'

  # Pretty sure there are no English words that contain `cns`, and luckily none of our committed public keys and hashes toes either.
  rename "cns to ans" \
    "'cns///ans'" \
    "" \
    ""
  rename "Cns to Ans" \
    "'Cns///Ans'" \
    "" \
    ""
  rename "CNS to ANS" \
    "'CNS///ANS'" \
    "" \
    ""

  rename "Canton Name to Amulet Name" \
    "'\bCanton Name\b///Amulet Name'" \
    "" \
    ""

  rename "Canton name to Amulet name" \
    "'\bCanton name\b///Amulet name'" \
    "" \
    ""

  run_and_commit_rename "cns to ans: auth0.ts" "'cns///ans'" "-i '**/auth0.ts'"
  run_and_commit_rename "Cns to Ans: auth0.ts" "'Cns///Ans'" "-i '**/auth0.ts'"
  run_and_commit_rename "CNS to ANS: auth0.ts" "'CNS///ANS'" "-i '**/auth0.ts'"

  run_and_commit_rename "Fix docs link in release notes" "'helm-cns-web-ui///helm-ans-web-ui'" "-i '**/release_notes.rst'"
}

function subcmd_internal_cns_occs() {
  assert_clean_working_dir

  commit_occurrences "cns"
  commit_occurrences "Cns"
  commit_occurrences "CNS"
  commit_occurrences "Canton Name"
  commit_occurrences "Canton name"
}

subcommand_whitelist[cns_ans]='Rename: CNS to ANS'

function subcmd_cns_ans() {
  subcmd_internal_cns_ans
  subcmd_internal_cleanup
  subcmd_internal_cns_occs
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

## Dso Member


subcommand_whitelist[dso_member]='Rename: DSO member usages to SV'

function subcmd_dso_member() {
  assert_clean_working_dir

  # Ignore files with unrelated mentions of 'member'
  local ignore_unrelated=" \
    -e '*/splitwell/*' \
    -e '*/splitwell-test/*' \
    -e '**/AppUpgradeIntegrationTest.scala' \
    -e '**/Wallet/TopUpState.daml' \
    -e '**/Wallet/BuyTrafficRequest.daml' \
    -e '**/Wallet/Install.daml'"
  local daml_only="-i 'daml/**' $ignore_unrelated"

  # Rename instances of 'Member' that are not related to 'Traffic'
  simple_rename "\bMemberState\b///SvState" "$ignore_unrelated"
  simple_rename "\bmemberstate\b///svstate" "$ignore_unrelated"
  simple_rename "MemberRewardState///SvRewardState" "$ignore_unrelated"
  simple_rename "\bForMember\b///ForSv" "$ignore_unrelated"
  simple_rename "MemberInfo///SvInfo" "$ignore_unrelated"
  simple_rename "\bOffboardedMemberInfo\b///OffboardedSvInfo" "$ignore_unrelated"
  simple_rename "AddMember///AddSv" "$ignore_unrelated"
  simple_rename "OffboardMember///OffboardSv" "$ignore_unrelated"
  simple_rename "_AddConfirmedMember///_AddConfirmedSv" "$ignore_unrelated"
  simple_rename "(?<=offboarded|abstaining)Members///Svs" "$ignore_unrelated"
  simple_rename "(?<=(new|per|num|Per|add|non))Member(?!.*affic)///Sv" "$ignore_unrelated"
  simple_rename "(?<=\bmaybe)Member///Sv" "$ignore_unrelated"
  simple_rename "(?<=\bactive)Member///Sv" "$ignore_unrelated"
  simple_rename "member(?=(Reward|Info))///sv" "$ignore_unrelated"

  # Targeted fixups
  simple_rename "DSO member///SV" "$ignore_unrelated"
  simple_rename "SV member///SV" "$ignore_unrelated"
  simple_rename "collective member///SV" "$ignore_unrelated"

  simple_rename "Membership///Sv" "$daml_only"
  simple_rename "SvMember///Sv" "$daml_only"
  simple_rename "membership///SV" "$daml_only"

  # A hard one: members field of DsoRules
  simple_rename "members///svs" "$daml_only"

  simple_rename "members(?=(Map[.]Map| &&| of| =))$///svs" "-i 'daml/splice-dso-governance/**' $ignore_unrelated"
  simple_rename "(?<=[.])members\b///svs" "-i 'daml/**' $ignore_unrelated"
  simple_rename "(?<=payload[.])members///svs" "$ignore_unrelated"
  simple_rename "payload\n\s*\.members///payload.svs" "$ignore_unrelated"
  simple_rename "dsoRulesBeforeElection.members///dsoRulesBeforeElection.svs" "$ignore_unrelated"

  # Another hard one: remaining usages of 'member'
  simple_rename "(?<!(Map|Set)[.])member(?!(.*affic|Id))///sv" "$daml_only"

  simple_rename "members: \\[///svs: [" "$ignore_unrelated"
  simple_rename "{ member: member }///{ sv: member }" "$ignore_unrelated"
  simple_rename "dsoAction.value.member///dsoAction.value.sv" "$ignore_unrelated"
  simple_rename "OffboardSvValue.member///OffboardSvValue.sv" "$ignore_unrelated"

  # Second round of fixups
  simple_rename "Member(?!.*affic)///SV" "$daml_only"
  simple_rename "\ba SV\b///an SV" "$ignore_unrelated"
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

function rename_daml_package_raw() {
  local old=$1
  local new=$2
  local resolverOld=$3
  local resolverNew=$4

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

  rename "Package resolver code for $resolverOld" \
    "'(?<=case )$resolverOld\b|(?<=Package[.])$resolverOld\b|\b$resolverOld(?= extends Package)///$resolverNew'" \
    "" \
    ""
}

function rename_daml_package() {
  local old=$1
  local new=$2
  local resolverOld=$3
  local resolverNew=$4

  rename_daml_package_raw "$old" "$new" "$resolverOld" "$resolverNew"
  rename_daml_package_raw "$old-test" "$new-test" "${resolverOld}Test" "${resolverNew}Test"
}

subcommand_whitelist[cn_util_package]='Rename: Daml package cn-util to splice-util'
function subcmd_cn_util_package() {
  rename_daml_package 'cn-util' 'splice-util' 'CnUtil' 'SpliceUtil'
}

subcommand_whitelist[all_packages]='Rename: all Daml packages (except splitwell) to their splice counter-part'
function subcmd_all_packages() {
  rename_daml_package 'cn-util' 'splice-util' 'CnUtil' 'SpliceUtil'
  rename_daml_package 'wallet' 'splice-wallet' 'Wallet' 'SpliceWallet'
  rename_daml_package 'wallet-payments' 'splice-wallet-payments' 'WalletPayments' 'SpliceWalletPayments'
  rename_daml_package 'validator-lifecycle' 'splice-validator-lifecycle' 'ValidatorLifecycle' 'SpliceValidatorLifecycle'
  rename_daml_package 'dso-governance' 'splice-dso-governance' 'DsoGovernance' 'SpliceDsoGovernance'
  rename_daml_package 'canton-amulet' 'splice-amulet' 'CantonAmulet' 'SpliceAmulet'
  rename_daml_package 'app-manager' 'splice-app-manager' 'AppManager' 'SpliceAppManager'
  rename_daml_package 'canton-name-service' 'splice-amulet-name-service' 'CantonNameService' 'SpliceAmuletNameService'
}

# Domain -> Synchronizer

subcommand_whitelist[internal_global_domain_synchronizer]='Rename: GlobalDomain to something with Synchronizer (mainly Daml)'

function subcmd_internal_global_domain_synchronizer() {
  assert_clean_working_dir

  # We can't assert this because we already say this in the docs in a few places
  assert_no_usage 'decentralizedSynchronizer|DecentralizedSynchronizer|synchronizerId|activeSynchronizer|requiredSynchronizers|SynchronizerFee|UnknownSynchronizer'
  assert_no_canton_usage 'decentralizedSynchronizer|DecentralizedSynchronizer|synchronizerId|activeSynchronizer|requiredSynchronizers|SynchronizerFee|UnknownSynchronizer'

  # stuff used in daml
  rename "globalDomain to decentralizedSynchronizer" \
    "'globalDomain///decentralizedSynchronizer'" \
    "" \
    ""
  rename "GlobalDomain to DecentralizedSynchronizer" \
    "'GlobalDomain///DecentralizedSynchronizer'" \
    "" \
    ""
  rename "DomainState to SynchronizerState" \
    "'\bDomainState\b///SynchronizerState'" \
    "" \
    ""
  rename "domainNode to synchronizerNode" \
    "'domainNode///synchronizerNode'" \
    "" \
    ""
  rename "DomainNode to SynchronizerNode" \
    "'DomainNode///SynchronizerNode'" \
    "" \
    ""
  rename "domain-node to synchronizer-node" \
    "'domain-node///synchronizer-node'" \
    "" \
    ""
  rename "DomainConfig to SynchronizerConfig" \
    "'DomainConfig///SynchronizerConfig'" \
    "" \
    ""
  rename "participantDomainTime to participantSynchronizerTime" \
    "'participantDomainTime///participantSynchronizerTime'" \
    "" \
    ""
  rename "mediatorDomainTime to mediatorSynchronizerTime" \
    "'mediatorDomainTime///mediatorSynchronizerTime'" \
    "" \
    ""
  rename "DomainUpgrade to SynchronizerUpgrade" \
    "'DomainUpgrade///SynchronizerUpgrade'" \
    "" \
    ""
  rename "activeDomain to activeSynchronizer" \
    "'activeDomain///activeSynchronizer'" \
    "" \
    ""
  rename "requiredDomains to requiredSynchronizers" \
    "'requiredDomains///requiredSynchronizers'" \
    "" \
    ""
  rename "DomainFee to SynchronizerFee" \
    "'DomainFee///SynchronizerFee'" \
    "" \
    ""
  rename "initial-domain-fees-config to initial-synchronizer-fees-config" \
    "'initial-domain-fees-config///initial-synchronizer-fees-config'" \
    "" \
    ""
  rename "UnknownDomain to UnknownSynchronizer" \
    "'UnknownDomain///UnknownSynchronizer'" \
    "" \
    ""
  rename "DSO.GlobalDomain to DSO.DecentralizedSynchronizer" \
    "'DSO.GlobalDomain///DSO.DecentralizedSynchronizer'" \
    "" \
    ""
  rename "Splice.GlobalDomain to Splice.DecentralizedSynchronizer" \
    "'Splice.GlobalDomain///Splice.DecentralizedSynchronizer'" \
    "" \
    ""
  rename "globaldomain to decentralizedsynchronizer" \
    "'globaldomain///decentralizedsynchronizer'" \
    "" \
    ""

  # domain id hacks; they work! + easier than manual fixes
  run_and_commit_rename "payload.domainId to payload.synchronizerId: scala files" \
    "'payload.domainId///payload.synchronizerId'" \
    "-i '*.scala' -e 'canton/**'"
  run_and_commit_rename "data.domainId to data.synchronizerId: scala files" \
    "'data.domainId///data.synchronizerId'" \
    "-i '*.scala' -e 'canton/**'"
  run_and_commit_rename "r.domainId} to r.synchronizerId}: scala files" \
    "'r.domainId}///r.synchronizerId}'" \
    "-i '*.scala' -e 'canton/**'"

  # dso info mock for sv ui tests
  run_and_commit_rename "globalDomain to decentralizedSynchronizer: sv constants.ts" \
    "'globalDomain///decentralizedSynchronizer'" \
    "-i 'apps/sv/**/constants.ts'"
  run_and_commit_rename "domain to synchronizer: sv constants.ts" \
    "'domain///synchronizer'" \
    "-i 'apps/sv/**/constants.ts'"
  run_and_commit_rename "Domain to Synchronizer: sv constants.ts" \
    "'Domain///Synchronizer'" \
    "-i 'apps/sv/**/constants.ts'"

  # remaining stuff in daml (mostly comments, but also fixes domainId)
  run_and_commit_rename "Global to Decentralized: daml files (mostly comments)" \
    "'Global///Decentralized'" \
    "-i 'daml/**'"
  run_and_commit_rename "global to decentralized: daml files (mostly comments)" \
    "'global///decentralized'" \
    "-i 'daml/**'"
  run_and_commit_rename "Domain to Synchronizer: daml files (mostly comments)" \
    "'Domain///Synchronizer'" \
    "-i 'daml/**'"
  run_and_commit_rename "domain to synchronizer: daml files (mostly comments)" \
    "'domain///synchronizer'" \
    "-i 'daml/**'"
}

subcommand_whitelist[global_domain_synchronizer]='Rename: GlobalDomain to something with Synchronizer'

function subcmd_global_domain_synchronizer() {
  subcmd_internal_global_domain_synchronizer
  subcmd_internal_cleanup
}

### Remove usage of Canton

subcommand_whitelist[remove_canton]='Rename: cantonAmulet and cantonNameService'

function subcmd_remove_canton() {
  assert_clean_working_dir

  # Ignore files with unrelated mentions of 'member'
  local ignore_unrelated=""
  local daml_only="-i 'daml/**' $ignore_unrelated"

  # Rename instances of 'Member' that are not related to 'Traffic'
  simple_rename "cantonAmulet///amulet" "$ignore_unrelated"
  simple_rename "cantonNameService///amuletNameService" "$ignore_unrelated"
}


### UI cleanup - ANS

subcommand_whitelist[ui_cleanup_ans]='Rename: UI cleanup to use CNS instead of ANS'

function subcmd_ui_cleanup_ans() {
  assert_clean_working_dir

  # Ignore files with unrelated mentions of 'member'
  local ignore_unrelated="-e 'daml/**'"
  local frontend_only="-i '*.tsx' -i '*.ts' -i '**/test/**/*.scala' $ignore_unrelated"

  simple_rename "(?<=unverified[.])ans///cns" "$ignore_unrelated"
  # Regexes for ANS entries
  simple_rename "(?<=[\\\\][.])ans///cns" "$ignore_unrelated"
  simple_rename "(?<=dso[.])ans///cns" "$ignore_unrelated"
  simple_rename "(?<=sv[.])ans///cns" "$ignore_unrelated"

  simple_rename "Amulet Name Service///Canton Name Service" "$frontend_only"
  simple_rename "ANS(?=( entry| Entry| entries| Entries))///CNS" "$frontend_only"
  simple_rename "ANS(?=( client))///CNS" "$frontend_only"
  simple_rename "ANS(?=( and | ui| UI))///CNS" "$frontend_only"
}

### UI cleanup no Amulet

subcommand_whitelist[ui_cleanup_amulet]='Rename: UI cleanup to use Canton Coin instead of Amulet'
function subcmd_ui_cleanup_amulet() {
  assert_clean_working_dir

  local ignore_unrelated="-e 'daml/**'"
  local frontend_only="-i '*.tsx' -i '*.ts' -i '**/*.scala' $ignore_unrelated"

  simple_rename "Amulet(?=(/USD|/Round))///CC" "$frontend_only"
  simple_rename "Amulet(?=( Operation| Expired| Unlocked| Owner| Balance| Creation| Price))///Canton Coin" "$frontend_only"
}


### Static Check

subcommand_whitelist[no_illegal_daml_references]='Check for illegal daml references'
function subcmd_no_illegal_daml_references() {
    local illegal_words=(
      currency founder founding leader collective consortium
      coin cn whitepaper canton
      domain global
      DsoReward
      'google'
      )
    for word in "${illegal_words[@]}"; do
        echo "Checking for occurences of '$word' (case-insensitive)"
        if rg -i "$word" daml/; then
            echo "$word occurs in Daml code, remove all references"
            exit 1
        fi
    done
    local illegal_patterns=(
      svc SVC Svc   # to avoid conflict with PerSvContracts
      '(?<![a-z])cc(?!(ept|essor|g[.]github))'
      CC
      '(?<!(Map|Set)[.])(?<!sequencer )member(?!(Id|.*[tT]raffic))'
      # Allow only Dso as in DsoRules in comments
      '[-][-] .*Dso(?!(Rules))'
      # Disallow dso in comments other than dsoParty
      '[-][-] .*dso'
      # Allow only very specific mentions of DSO
      '(?<!standard )DSO(?!([.]| party| rules| delegate| governance|-level))'
      # No connection between DSO and issuance
      '(dso|Dso|DSO).*ssue'
      'ssue.*(dso|Dso|DSO)'
      # No Github issue links
      'github(?!.io/hashlink)'

      )
    for pattern in "${illegal_patterns[@]}"; do
        echo "Checking for occurences of '$pattern' (case sensitive, in code other than splitwell)"
        if rg -P "$pattern" daml/ -g '!*/splitwell/*' -g '!*/splitwell-test/*' -g '!daml/dars.lock'; then
            echo "$pattern occurs in Daml code (other than splitwell), remove all references"
            exit 1
        fi
    done

    # Using this cheap way to make the UI check a static check as well
    echo ""
    echo "Also checking frontend code:"
    subcmd_no_amulet_in_ui
}

subcommand_whitelist[no_amulet_in_ui]='Check for Amulet and ANS in user UI'
function subcmd_no_amulet_in_ui() {
    local illegal_patterns=(
      'ANS(?!_LEDGER_NAME)'
      "(?<!Splice[./])\bAmulet\b"
      )
    for pattern in "${illegal_patterns[@]}"; do
        echo "Checking for occurences of '$pattern' in frontend code"
        if rg -P "$pattern" -g '*.tsx' -g '*.ts' -g '**test/**/*.scala' -g '!cluster/**'; then
            echo "$pattern occurs in frontend, ensure it is not user-visible"
            exit 1
        fi
    done
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
