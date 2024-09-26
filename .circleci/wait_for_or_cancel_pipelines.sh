#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

sudo apt-get update
sudo apt-get install python3-pip -y
python3 -m pip install -r .circleci/requirements.txt

"${SCRIPT_DIR}/wait_for_or_cancel_pipelines.py" "$@"
