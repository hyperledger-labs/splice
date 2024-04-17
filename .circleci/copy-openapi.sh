#!/usr/bin/env bash
mkdir -p /tmp/artifacts
mkdir -p /tmp/openapi
cp "apps/common/src/main/openapi/README.md" /tmp/openapi;
find . -ipath '*/openapi/*.yaml' -exec cp '{}' /tmp/openapi \;
cd /tmp/openapi || exit
tar -czvf openapi.tar.gz -- *
mv openapi.tar.gz ../artifacts
