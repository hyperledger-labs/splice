#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from prometheus_client import start_http_server, Gauge
import time
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--test_suite', required=True)
args = parser.parse_args()

g = Gauge('gha_runner_info', 'GHA runner information', ['test_suite'])
g.labels(test_suite = args.test_suite).set(1)
start_http_server(8000)

while True:
  time.sleep(10)
