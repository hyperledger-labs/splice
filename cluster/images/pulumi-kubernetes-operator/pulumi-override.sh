#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# The operator doesn't support overriding parallelism by default so we just add it by default here
# Checking the automation api, it should call pulumi just for  ops that support parallelism



if [[ "$*" == *"up"* ]]
then
  echo "Running pulumi with parallelism 128"
  pulumi-original --parallel 128 "$@"
else
  pulumi-original "$@"
fi
