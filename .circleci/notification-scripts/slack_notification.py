# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import json
import requests
import sys

from circleci import *
from failure_notification_args import FailureArgs

def build_msg(args: FailureArgs, gh_url: str):
  workflow = fetch_workflow(args.workflow_id)

  text=f"""*Job {workflow.name}:{args.job_name} {workflow.pipeline_number}:{args.job_num} failed on cluster {args.cluster}* :dumpster-fire:.
  (<{gh_url}|Issue in GitHub>)"""

  return text

def slack_notification(args: FailureArgs, gh_url: str):

  if not re.match(args.branch_pattern, args.branch):
    print(f"Branch {args.branch} does not match pattern {args.branch_pattern}, skipping notification")
    sys.exit(0)

  text = build_msg(args, gh_url)
  msg = {"text": text, "channel": args.slack_channel}

  print(f"Notification: {json.dumps(msg)}")

  if not args.dry_run:
    requests.post(
      "https://slack.com/api/chat.postMessage",
      json=msg,
      headers={
        "Authorization": f"Bearer {os.environ.get('SLACK_ACCESS_TOKEN')}",
        "Content-Type": "application/json"})

