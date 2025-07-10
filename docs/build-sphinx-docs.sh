#!/usr/bin/env bash

set -eou pipefail

root_dir=$(cd "$(dirname $0)"; cd ..; pwd -P)
input_dir="${root_dir}/docs/src"
preview_dir="${root_dir}/docs/preview"
config_dir="${root_dir}/docs/src"

# Let's remove the dir to be 100% sure there are no stale files
# The build is relatively small and we can afford it
rm -Rf $preview_dir

sphinx-build -M html $input_dir $preview_dir --fresh-env --conf-dir $config_dir --fail-on-warning

# Make the generated files read-only
find $preview_dir -type f -follow -exec chmod 0444 {} +

if [[ $# -gt 0 && "$1" == "--with-preview" ]]; then
  python -m http.server -d ./doc/preview/html
fi


#####
# install
#####
# brew install pyenv
# pyenv install 3.10.13
# cd /Users/simonletort/Documents/GitHub/splice
# pyenv local 3.10.13
# pyenv virtualenv 3.10.13 splice-docs
# pyenv local splice-docs
# pip install sphinx sphinx-autobuild pyyaml
# which python
# python --version
