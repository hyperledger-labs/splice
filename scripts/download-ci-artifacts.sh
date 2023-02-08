#!/usr/bin/env bash
# Usage: ./scripts/download-ci-artifacts.sh and enter the ~5-digit CircleCI job number when prompted

set -eou pipefail

echo "This script downloads all build artifacts from a CircleCI job"

CIRCLECI_CONFIG_FILE="$HOME/.circleci/cli.yml"
if [ ! -f "$CIRCLECI_CONFIG_FILE" ]; then
    echo "$CIRCLECI_CONFIG_FILE does exists. Please run 'circleci setup' to authenticate."
fi
CIRCLE_TOKEN=$(cat "$CIRCLECI_CONFIG_FILE" | grep token | sed -e "s/^token: //")

CI_VCSTYPE="github"
CI_ORGNAME="DACH-NY"
CI_PROJECT="the-real-canton-coin"

echo "Please enter the job number. This should be a ~5 digit number."
read CI_BUILD_NUM

BASE_TARGET_DIRECTORY="$(pwd)/log/ci/$CI_BUILD_NUM"
mkdir -p "$BASE_TARGET_DIRECTORY"
echo "Saving all artifacts to $BASE_TARGET_DIRECTORY"

ARTIFACTS_URL=https://circleci.com/api/v1.1/project/$CI_VCSTYPE/$CI_ORGNAME/$CI_PROJECT/$CI_BUILD_NUM/artifacts?circle-token=$CIRCLE_TOKEN
curl $ARTIFACTS_URL -s | grep -o 'https://[^"]*' > "$BASE_TARGET_DIRECTORY/artifacts.txt"

while read p; do
  if [[ $p =~ ".log.gz" ]] || [[ $p =~ ".clog.gz" ]]; then
    PARALLEL_RUN="$(echo $p | sed -e 's?.*\/artifacts\/??' | sed -e 's?\/.*??')"
    if [ -z "$PARALLEL_RUN" ]; then
      TARGET_DIRECTORY="$BASE_TARGET_DIRECTORY/logs"
    else
      TARGET_DIRECTORY="$BASE_TARGET_DIRECTORY/run-$PARALLEL_RUN"
    fi
    mkdir -p "$TARGET_DIRECTORY"
    FILE_NAME="$(echo $p | sed -e 's?.*\/??')"
    TARGET_PATH="$TARGET_DIRECTORY/$FILE_NAME"
    echo "Downloading artifact $p to $TARGET_PATH"
    curl -sSL -o "$TARGET_PATH" "$p?circle-token=$CIRCLE_TOKEN"
    gzip -f -d "$TARGET_PATH"
  else
    echo "Not downloading $p, as it is not a log file"
  fi
done <"$BASE_TARGET_DIRECTORY/artifacts.txt"

echo "Done. Logs have been downloaded to the following directory:"
echo "$BASE_TARGET_DIRECTORY"
