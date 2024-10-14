#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from slack_notification import slack_notification
from failure_github_issue import *
from failure_notification_args import *

if __name__ == "__main__":
  args = parse_args()
  gh_url = failure_github_issue(args)
  slack_notification(args, gh_url)
