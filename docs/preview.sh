#!/usr/bin/env bash
set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

(cd "$REPO_ROOT"; sbt docs/bundle)

cd html/html
python -m http.server 8000 --bind 127.0.0.1
