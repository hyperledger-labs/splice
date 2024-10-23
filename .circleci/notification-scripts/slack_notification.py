# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import json
import requests
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from circleci import *
from failure_notification_args import FailureArgs

def build_msg(args: FailureArgs, gh_url: str):
  workflow = fetch_workflow(args.workflow_id)
  stats = failures_and_last_success(
    workflow.pipeline_number, args.branch, workflow, args.job_num)

  text=f"""*Job {workflow.name}:{args.job_name} {workflow.pipeline_number}:{args.job_num} failed on cluster {args.cluster}* :dumpster-fire:.
  (<{gh_url}|Issue in GitHub>)
  {failure_stat(f"workflow {workflow.name}", stats.failed_workflows, stats.last_workflow_success, stats)}
  {failure_stat(f"Job {args.job_name}", stats.failed_jobs, stats.last_job_success, stats)}"""

  return text

def failure_stat(what: str, failures: int, last_success: datetime | None, windows: SuccessStats):
  if failures:
    pretty_success = (f"Last success: {last_success}" if last_success
                      else f"No successes in {pretty_timedelta(windows.success_window)}")
    return f"{what} failed {failures} times in last {pretty_timedelta(windows.failure_window)} ({pretty_success})"
  else:
    return (f"Last {what} success: {last_success}" if last_success
            else f"No {what} success in last {pretty_timedelta(windows.success_window)}")

# incomplete but good enough for our cases
def pretty_timedelta(td: timedelta):
  s = int(td.total_seconds())
  return f"{s // 86400} days" if s >= 86400 else f"{s // 3600} hours"

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

