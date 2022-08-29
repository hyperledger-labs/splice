#!/bin/bash

set -eou pipefail

# This script is a simplified version of Canton's analogue create-bundle.sh script
# Usage: `./create-bundle.sh [-c <directory-to-copy-to-release-bundle]* [-r <file-location> <file-name-and-location-in-release-bundle>]*
# where "-c" stands for "copy" and "-r" stands for "rename"

# The app "binary" is just a shell script that calls the main JAR
function adjust_shellscript_binary() {
  REPLACE_VERSION=$(echo "$JAR" | sed -E 's/.*coin-([^-]+)-.*/\1/')
  REPLACE_REVISION=$(git rev-parse HEAD)
  REPLACE_JVM_OPTS="-XX:+CrashOnOutOfMemoryError"
  REPLACE_JAR="lib\/$JAR"
#  REPLACE_MAC_ICON_FILE="lib\/canton.ico"
  cp -r "$RELEASE_DIR/../../../src/pack/bin" "$RELEASE_DIR"
  for file in "bin/coin" # TODO(i161): Canton supports windows. Do we want that too? "bin/coin.bat"
  do
      cat "$RELEASE_DIR"/$file |
        sed -e "s/REPLACE_VERSION/${REPLACE_VERSION}/" |
        sed -e "s/REPLACE_REVISION/${REPLACE_REVISION}/" |
        sed -e "s/REPLACE_JVM_OPTS/${REPLACE_JVM_OPTS}/" |
        sed -e "s/REPLACE_JAR/${REPLACE_JAR}/" > $RELEASE_DIR/tmp.txt
        # TODO(i161): Look into this Mac Icon
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


shift # shift JARFILE argument out-of-scope
ARGS=$@ # other command line args, given in form

state="scan"

for arg in $ARGS
do
  case $state in
    "scan")
      case $arg in
        "-c")
          state="copy"
          ;;
        "-r")
          state="rename"
          ;;
        *)
          echo "ERROR, expected -r or -c, found $arg"
          exit 1
      esac
      ;;
    "copy")
      if [[ -e $arg ]]; then
        if [[ -d $arg ]]; then
          if [[ -z $(ls -A $arg) ]]; then
            echo "skipping empty $arg"
          else
            echo "copying content from $arg"
            cp -r $arg/* "$RELEASE_DIR"
          fi
        else
          echo "copying file $arg"
          cp $arg "$RELEASE_DIR"
        fi
      else
        echo "ERROR, no such file $arg for copying"
        exit 1
      fi
      state="scan"
      ;;
    "rename")
      if [[ -e $arg ]]; then
        rename=$arg
      else
        echo "ERROR, no such file $arg for renaming"
        exit 1
      fi
      state="rename-do"
      ;;
    "rename-do")
      target=$RELEASE_DIR/$arg
      target_dir=$(dirname "$target")
      if [[ ! -e $target_dir ]]; then
        mkdir -p "$target_dir"
      fi
      # shellcheck disable=SC2086
      cp -v $rename $target
      state="scan"
      ;;
    *)
      echo "unexpected state $state"
      exit 1
  esac
done

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
