#!/usr/bin/env bash
apps=("common" "wallet" "validator" "sv" "splitwell" "scan" "cns" "app-manager")
mkdir -p /tmp/artifacts
mkdir -p /tmp/openapi
cp "apps/common/src/main/openapi/README.md" /tmp/openapi;
for app in "${apps[@]}"; do
  cp "apps/$app/src/main/openapi/$app-external.yaml" /tmp/openapi || :
  cp "apps/$app/src/main/openapi/$app-internal.yaml" /tmp/openapi || :
done
cd /tmp/openapi || exit
tar -czvf openapi.tar.gz -- *
mv openapi.tar.gz ../artifacts
