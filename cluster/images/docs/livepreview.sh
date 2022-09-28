#!/usr/bin/env bash
set -eou pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

./gen-daml-docs.sh

sphinx-autobuild src target
