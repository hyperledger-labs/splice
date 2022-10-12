#!/usr/bin/env bash

# create-react-app gets unhappy about symlinks so we copy the files
# https://github.com/facebook/create-react-app/issues/3547.

mkdir -p src/components src/utils
rsync -qa ../../common/frontend/src/ --exclude com --exclude index.ts src/
