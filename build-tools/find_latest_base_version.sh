#!/bin/bash

set -euo pipefail

latest_release=$(cat "LATEST_RELEASE")
echo "release-line-${latest_release}"
