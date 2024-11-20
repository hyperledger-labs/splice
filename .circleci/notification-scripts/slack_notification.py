# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
from datetime import datetime, timezone
import json
import requests
import sys
from humanize import naturaldelta

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from circleci import *
from failure_notification_args import FailureArgs

def build_msg(args: FailureArgs, gh_url: str):
  workflow = fetch_workflow(args.workflow_id)
  stats = failures_and_last_success(
    workflow.pipeline_number, args.branch, workflow, args.job_name)

  cluster_text = f" on cluster {args.cluster}" if args.cluster else ""
  text=f"""*Job {workflow.name}:{args.job_name} {workflow.pipeline_number}:{args.job_num} failed {cluster_text}:dumpster-fire:.
  (<{gh_url}|Issue in GitHub>)
  {render_success_stats(stats, workflow, args)}"""

  return text

def render_success_stats(stats: str | SuccessStats, workflow: Workflow, args: FailureArgs):
  if isinstance(stats, str):
    return stats
  else:
    now = datetime.now(timezone.utc)
    return f"""{failure_stat(f"workflow {workflow.name}", stats.failed_workflows, stats.last_workflow_success, stats, now)}
  {failure_stat(f"Job {args.job_name}", stats.failed_jobs, stats.last_job_success, stats, now)}"""

def failure_stat(what: str, failures: int, last_success: datetime | None, windows: SuccessStats, now: datetime):
  if failures:
    pretty_success = (f"Last success: {naturaldelta(now - last_success)} ago" if last_success
                      else f"No successes in {naturaldelta(windows.success_window)}")
    return f"{what} failed {failures} times in last {naturaldelta(windows.failure_window)} ({pretty_success})"
  else:
    return (f"Last {what} success: {naturaldelta(now - last_success)} ago" if last_success
            else f"No {what} success in last {naturaldelta(windows.success_window)}")

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

