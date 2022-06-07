#!/usr/bin/env bash
set -eou pipefail

make html
cd _build/html
python -m http.server 8000 --bind 127.0.0.1
