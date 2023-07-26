#!/usr/bin/env bash
apps=("common" "wallet" "validator" "sv" "splitwell" "scan" "directory" "app-manager")
mkdir -p /tmp/artifacts
mkdir -p /tmp/openapi
cp "apps/common/src/main/openapi/README.md" /tmp/openapi;
# TODO (#6888): only publish the ones with external tags
for app in "${apps[@]}"; do
  cp "apps/$app/src/main/openapi/$app.yaml" /tmp/openapi || :
done
cd /tmp/openapi || exit
tar -czvf openapi.tar.gz -- *
mv openapi.tar.gz ../artifacts
