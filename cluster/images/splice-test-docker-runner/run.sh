#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# wait for `docker version` to succeed, at most 30 iterations

for i in {1..30}; do
  if docker version &>/dev/null; then
    break
  fi
  echo "Waiting for Docker to be ready... ($i/30)"
  sleep 2
done

if ! docker version &>/dev/null; then
  echo "Docker did not start in time. Exiting."
  exit 1
fi

/home/runner/run-base.sh
