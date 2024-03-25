#! /bin/bash

SUBCOMMAND_NAME="$1"
shift

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

SCRIPTNAME=${0##*/}

cd "$REPO_ROOT"

### Env var flag defaulting

SKIP_USAGE_CHECKS=${SKIP_USAGE_CHECKS:-0}


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
    # Add all modified files to the staging area, excluding the rename/ directory
    git add -A
    git reset -- rename/

    # Commit the changes
    git commit -m "$description" -m "CMD: $cmd"
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
  git commit --allow-empty -m "$description" -m "CMD: $cmd" -m "$output"
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
  local extra_excludes=$3

  # Build the excludes in reverse order, exploiting the idempotency of the renames when applying them
  local SPECIAL="$extra_excludes $NO_PROTECTED $NO_LOCKS $NO_CANTON"
  local DOCS="$NO_SPECIAL $SPECIAL"
  local CRYPTO="$NO_DOCS $DOCS"
  local CLUSTER="$NO_CRYPTO $CRYPTO"
  local FRONTEND="$NO_CLUSTER $CLUSTER"
  local API="$NO_FRONTEND $FRONTEND"
  local APP="$NO_API $API"

  # Daml files are easier to specify as a separate group
  local DAML="-i 'daml/**' $SPECIAL"

  # We are not adjusting lock files, as they are regenerated in the cleanup step
  run_and_commit "renaming $description: daml files"      "gsr $DAML      -f $pattern"
  run_and_commit "renaming $description: app files"       "gsr $APP       -f $pattern"
  run_and_commit "renaming $description: api files"       "gsr $API       -f $pattern"
  run_and_commit "renaming $description: frontend files"  "gsr $FRONTEND  -f $pattern"
  run_and_commit "renaming $description: cluster files"   "gsr $CLUSTER   -f $pattern"
  run_and_commit "renaming $description: crypto files"    "gsr $CRYPTO    -f $pattern"
  run_and_commit "renaming $description: doc files"       "gsr $DOCS      -f $pattern"
  run_and_commit "renaming $description: special files"   "gsr $SPECIAL   -f $pattern"

}

function commit_occurrences() {
  local word=$1

  local cmd="git grep $word -- ':!$rename_script'"

  _info "Checking and storing left-over occurrences of '$word'"
  eval "$cmd"
  run_and_store_output "left-over occurrences of '$word'" "$cmd"
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
  run_and_commit "cleanup: format .scala files" "sbt --client format"
  run_and_commit "cleanup: format .ts files" "sbt --client npmFix"
  run_and_commit "cleanup: regenerate .daml and .ts lock files" "sbt --client 'cn-clean; Test/compile; bundle; damlDarsLockFileUpdate'"
  run_and_commit "cleanup: regenerate pulumi expected.json files" "make cluster/pulumi/update-expected"

  git commit --allow-empty -m"Mark this renaming change as a [breaking] change."
}

### SVC


subcommand_whitelist[internal_svc_dso_rename]='Internal - rename: svc to dso'

function subcmd_internal_svc_dso_rename() {
  assert_clean_working_dir

  if [[ $SKIP_USAGE_CHECKS != 1 ]]; then
    assert_no_usage 'dso|Dso|DSO'
  fi

  assert_no_canton_usage 'svc|Svc|SVC'

  rename "svc to dso" \
    "'(?<!(clouddns-dns01-solver-))(\b|(?<=[_]))svc(\b|(?=([A-Z_]|rules|bootstrap)))///dso'" \
    "-e '**/Pulumi.*.yaml'" # There is an 'svc' string in one of the hashes :('
  rename "Svc to Dso" \
    "'(\b|(?<=([a-z_])))Svc(\b|(?=([A-Z_])))///Dso'" \
    ""
  rename "SVC to DSO" \
    "'\bSVC(\b|(?=([_]|Rules)))///DSO'" \
    ""
}


subcommand_whitelist[internal_svc_dso_occs]='Internal - Check and store occurrences for: svc'

function subcmd_internal_svc_dso_occs() {
  assert_clean_working_dir

  commit_occurrences "svc"
  commit_occurrences "Svc"
  commit_occurrences "SVC"
}


subcommand_whitelist[svc_dso]='Rename: svc to dso (run with SKIP_USAGE_CHECKS=1 to fixup merge conflicts)'

function subcmd_svc_dso() {
  subcmd_internal_svc_dso_rename
  subcmd_internal_cleanup
  subcmd_internal_svc_dso_occs
}


### Coin

# TODO(#11111): complete this part of the script

subcommand_whitelist[internal_coin_amulet]='Rename: coin to amulet'

function subcmd_internal_coin_amulet() {
  assert_clean_working_dir

  if [[ $SKIP_USAGE_CHECKS != 1 ]]; then
    assert_no_usage 'amulet|Amulet'
  fi

  assert_no_canton_usage 'amulet|Amulet'
  assert_no_canton_usage 'coin|Coin'

  # Fix and accidental typo that's in our codebase
  rename "coinCointractId to coinContractId" \
    "'\bcoinCointractId\b///coinContractId'" \
    ""
  rename "coin to amulet" \
    "'\bcoin\b///amulet'" \
    ""
  rename "Coin to Amulet" \
    "'\bCoin\b///Amulet'" \
    ""
}


subcommand_whitelist[internal_coin_occs]='Check and store occurrences for: coin'

function subcmd_internal_coin_occs() {
  assert_clean_working_dir

  commit_occurrences "coin"
  commit_occurrences "Coin"
  commit_occurrences "COIN"
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
    ""
  rename "Cns to Ans" \
    "'\bCns\b///Ans'" \
    ""
  rename "CNS to ANS" \
    "'\bCNS\b///ANS'" \
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


### Splice packages

# TODO(#11111): complete this part of the script

subcommand_whitelist[internal_splice_splitwell]='Rename: Daml splitwell package to splice-splitwell'

function subcmd_internal_split_splitwell() {
  assert_clean_working_dir

  # daml/splitwell
  # splitwell-daml
  # splitwell-0.1.0
  # splitwell-0.2.0
  # splitwell[a-zA-Z-]+dar
  # @daml.js/splitwell
  # no usage of gsr 'splitwell-(?!(0.1.0|0.2.0))[0-9.]+///foo'
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
