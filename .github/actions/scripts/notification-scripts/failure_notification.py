#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from failure_github_issue import FailureGithubIssue
from slack_notification import SlackNotification
from failure_notification_args import *
import requests
import sys
import os
import logging

logging.basicConfig(
  level=logging.INFO,
  format="%(asctime)s [%(levelname)s] %(message)s",
)

if __name__ == "__main__":
  args = parse_args()

  gh = FailureGithubIssue(args)
  (gh_title, gh_msg) = gh.get_msg()
  workflow_name = args.gha_workflow_name
  job_name = args.job_name

  slack_msg = SlackNotification(args).build_msg("##GH_ISSUE##")
  channel = os.environ.get('NOTIFICATION_SLACK_CHANNEL')

  if args.dry_run:
    logging.info("Dry run, not sending notification")
    logging.info("Issue title: %s", gh_title)
    logging.info("Issue body: %s", gh_msg)
    logging.info("Slack message: %s", slack_msg)
    sys.exit(0)

  token = os.environ.get('FAILURE_NOTIFICATIONS_TOKEN')
  if not token:
    logging.error("FAILURE_NOTIFICATIONS_TOKEN is not set")
    sys.exit(1)

  r = requests.post(
    url = os.environ.get('FAILURE_NOTIFICATIONS_URL'),
    headers = {
      "Authorization": f"Bearer {token}",
    },
    json={
      "issue": {
        "title": gh_title,
        "body": gh_msg,
        "workflow_name": workflow_name,
        "job_name": job_name,
      },
      "slack_notification": {
        "msg": slack_msg,
        "channel": channel,
      }
    },
  )
  if r.status_code != 200:
    logging.error(
      "Error sending notifications: HTTP %d %s: %s",
      r.status_code, r.reason, r.text,
    )
    sys.exit(1)
  else:
    logging.info("Sent notification: %s", r.text)
    sys.exit(0)
