#!/usr/bin/env bash
set -eou pipefail

make html
cd target/html
python -m http.server 8000 --bind 127.0.0.1
