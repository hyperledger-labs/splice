#!/usr/bin/env bash

set -eou pipefail

# Copy protobuf sources from SBT build to ts directory.
# We copy rather than generating directly in here since some frontends will depend on multiple apps,
# e.g., the wallet will eventually depend on the directory service as well.

rm -rf src/com/daml/network
cp -r ../../common/target/scala-2.13/src_managed/main/ts/* src/
cp -r ../../directory/target/scala-2.13/src_managed/main/ts/* src/
cp -r ../../wallet/target/scala-2.13/src_managed/main/ts/* src/
