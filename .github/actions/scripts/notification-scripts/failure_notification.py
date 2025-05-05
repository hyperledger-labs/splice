#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from failure_github_issue import *
from failure_notification_args import *
from failure_github_issue_gha import *
from slack_notification_gha import *

if __name__ == "__main__":
  print("Parsing arguments")
  args = parse_args()
  print(f"Creating issue with args {args}")
  gh_url = GHAFailureGithubIssue(args).report_failure()
  GHASlackNotification(args).send_notification(gh_url)
  print("Sent notification")
