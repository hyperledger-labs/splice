#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if ! (which pip > /dev/null 2>&1); then
  sudo apt-get update
  sudo apt-get install python3-pip -y
fi

python3 -m pip install -r .circleci/notification-scripts/requirements.txt
