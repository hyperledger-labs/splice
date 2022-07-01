#!/bin/bash

# Script is a simplified version of Canton's analogue create-bundle.sh script

# The app "binary" is just a shell script that calls the main JAR
function adjust_shellscript_binary() {
  REPLACE_VERSION=$(echo "$JAR" | sed -E 's/.*coin-([^-]+)-.*/\1/')
  REPLACE_REVISION=$(git rev-parse HEAD)
  REPLACE_JVM_OPTS="-XX:+CrashOnOutOfMemoryError"
  REPLACE_JAR="lib\/$JAR"
#  REPLACE_MAC_ICON_FILE="lib\/canton.ico"
  cp -r "$RELEASE_DIR/../../../src/pack/bin" "$RELEASE_DIR"
  for file in "bin/coin" # TODO(i147): Canton supports windows. Do we want that too? "bin/coin.bat"
  do
      cat "$RELEASE_DIR"/$file |
        sed -e "s/REPLACE_VERSION/${REPLACE_VERSION}/" |
        sed -e "s/REPLACE_REVISION/${REPLACE_REVISION}/" |
        sed -e "s/REPLACE_JVM_OPTS/${REPLACE_JVM_OPTS}/" |
        sed -e "s/REPLACE_JAR/${REPLACE_JAR}/" > $RELEASE_DIR/tmp.txt
        # TODO(i147): Look into this Mac Icon
#        sed -e "s/REPLACE_MAC_ICON_FILE/${REPLACE_MAC_ICON_FILE}/"
      mv "$RELEASE_DIR"/tmp.txt "$RELEASE_DIR"/$file
      chmod 755 $RELEASE_DIR/$file
  done
}


set -euo pipefail

JARFILE=$1
# e.g. coin-0.1.0-SNAPSHOT.jar
JAR=$(basename "$JARFILE")
# e.g. coin-0.1.0-SNAPSHOT
RELEASE=$(echo $JAR | sed -e 's/\.jar$//')

RELEASES_DIR=$(dirname $JARFILE)/../release
RELEASE_DIR=$RELEASES_DIR/$RELEASE
echo "Creating release $RELEASE"

rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"/lib "$RELEASE_DIR"/bin

cp -v "$JARFILE" "$RELEASE_DIR"/lib

# TODO(i147): This is where the respective Canton script has a lot (of very unreadable) bundling logic

adjust_shellscript_binary

# pack releases
cd "$RELEASES_DIR"
rm -f "${RELEASE}.tar.gz"
rm -f "${RELEASE}.zip"
tar -zcf "${RELEASE}.tar.gz" "$RELEASE" &
zip -rq "${RELEASE}.zip" "$RELEASE"/* &
wait

# finally, add a stable link to the directory
rm -f coin
ln -s "$RELEASE" coin

echo "Successfully created release bundle for release $RELEASE"
echo "Folders with binaries: $RELEASE_DIR/bin"
