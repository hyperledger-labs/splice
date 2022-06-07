#!/usr/bin/env bash
set -eou pipefail

sphinx-autobuild . _build/html
