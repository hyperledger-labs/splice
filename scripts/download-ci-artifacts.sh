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

TARGET_DIRECTORY="$(pwd)/log/ci/$CI_BUILD_NUM"
mkdir -p $TARGET_DIRECTORY
echo "Saving all artifacts to $TARGET_DIRECTORY"

ARTIFACTS_URL=https://circleci.com/api/v1.1/project/$CI_VCSTYPE/$CI_ORGNAME/$CI_PROJECT/$CI_BUILD_NUM/artifacts?circle-token=$CIRCLE_TOKEN
curl $ARTIFACTS_URL -s | grep -o 'https://[^"]*' > "$TARGET_DIRECTORY/artifacts.txt"

while read p; do
  if [[ $p =~ ".log.gz" ]]; then
    FILENAME="$TARGET_DIRECTORY/$(echo $p | sed -e 's?.*\/??')"
    echo "Downloading artifact $p to $FILENAME"
    curl -sSL -o "$FILENAME" "$p?circle-token=$CIRCLE_TOKEN"
    gzip -f -d "$FILENAME"
  else
    echo "Not downloading $p, as it is not a log file"
  fi
done <"$TARGET_DIRECTORY/artifacts.txt"

echo "Done. Run the following command to inspect the logs:"
echo "lnav $TARGET_DIRECTORY/*.log"
