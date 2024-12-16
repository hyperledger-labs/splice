#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from failure_github_issue import *
from failure_notification_args import *

if __name__ == "__main__":
  print("Parsing arguments")
  args = parse_args()
  print(f"Creating issue with args {args}")
  if os.environ.get('GITHUB_ACTION'):
    from failure_github_issue_gha import *
    from slack_notification_gha import *
    gh_url = GHAFailureGithubIssue(args).report_failure()
    GHASlackNotification(args).send_notification(gh_url)
  else:
    from failure_github_issue_cci import *
    from slack_notification_cci import *
    gh_url = CCIFailureGithubIssue(args).report_failure()
    CCISlackNotification(args).send_notification(gh_url)
  print("Sent notification")
