#!/usr/bin/env bash
set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

./gen-daml-docs.sh

make html
cd target/html
python -m http.server 8000 --bind 127.0.0.1
