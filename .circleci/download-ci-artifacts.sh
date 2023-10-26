#!/usr/bin/env bash
# Usage: .circleci/download-ci-artifacts.sh and enter the ~5-digit CircleCI job number when prompted
# Add your personal Circle CI API token to the CIRCLECI_TOKEN environment variable before running the script.

set -eou pipefail

echo "This script downloads all build artifacts from a CircleCI job"

function usage() {
  echo "Usage: ./download-ci-artifacts.sh <CI_BUILD_NUM> | <flags>"
  echo "Flags:"
  echo "  -h                                                    display help message"
  echo "  -j   <CI_BUILD_NUM>                                   cci job number"
  echo "  [-r   <RUN_NUM>]                                      cci run number (default all)"
}

while getopts "h:j:r:" arg; do
  case ${arg} in
    h)
      usage
      exit 0
      ;;
    j)
      CI_BUILD_NUM="${OPTARG}"
      ;;
    r)
      RUN_NUM="${OPTARG}"
      ;;
    ?)
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${CI_BUILD_NUM+x}" ]]; then
  CI_BUILD_NUM=$1
fi

if [ -n "${RUN_NUM-}" ]; then
  regex='https://[^"]*/'${RUN_NUM}'/[^"]*'
else
  regex='https://[^"]*'
fi

CI_VCSTYPE="github"
CI_ORGNAME="DACH-NY"
CI_PROJECT="canton-network-node"
BASE_TARGET_DIRECTORY="$(pwd)/log/ci/$CI_BUILD_NUM"
mkdir -p "$BASE_TARGET_DIRECTORY"
echo "Saving all artifacts to $BASE_TARGET_DIRECTORY"

ARTIFACTS_URL="https://circleci.com/api/v2/project/$CI_VCSTYPE/$CI_ORGNAME/$CI_PROJECT/$CI_BUILD_NUM/artifacts"

curl -s -u "${CIRCLECI_TOKEN}": -H "Content-Type: application/json" \
      "$ARTIFACTS_URL" | grep -o "${regex}" > "$BASE_TARGET_DIRECTORY/artifacts.txt"

while read -r p; do
  if [[ $p = *.log.gz ]] || [[ $p = *.clog.gz ]]; then
    PARALLEL_RUN="$(echo "$p" | sed -e 's?.*\/artifacts\/??' | sed -e 's?\/.*??')"
    if [ -z "$PARALLEL_RUN" ]; then
      TARGET_DIRECTORY="$BASE_TARGET_DIRECTORY/logs"
    else
      TARGET_DIRECTORY="$BASE_TARGET_DIRECTORY/run-$PARALLEL_RUN"
    fi
    mkdir -p "$TARGET_DIRECTORY"
    FILE_NAME="${p##*/}"
    TARGET_PATH="$TARGET_DIRECTORY/$FILE_NAME"
    echo "Downloading artifact $p to $TARGET_PATH"
    curl -sSLf -o "$TARGET_PATH" "$p?circle-token=$CIRCLECI_TOKEN"
  else
    echo "Not downloading $p, as it is not a log file"
  fi
done <"$BASE_TARGET_DIRECTORY/artifacts.txt"

echo ""
echo "Done. You can now inspect the logs using:"
echo "lnav $BASE_TARGET_DIRECTORY/*"
