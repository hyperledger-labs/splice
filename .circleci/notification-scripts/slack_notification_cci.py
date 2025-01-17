# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from slack_notification import *
import os
import sys

from datetime import datetime, timezone
from humanize import naturaldelta

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from circleci import *
from failure_notification_args import FailureArgs

class CCISlackNotification(SlackNotification):

  def __init__(self, args: FailureArgs):
    self.workflow = fetch_workflow(args.workflow_id)
    super().__init__(args)

  def build_msg(self, gh_url: str):
    stats = failures_and_last_success(
      self.workflow.pipeline_number, self.args.branch, self.workflow, self.args.job_name)

    cluster_text = f" on cluster {self.args.cluster}" if self.args.cluster else ""
    text=f"""*Job {self.workflow.name}:{self.args.job_name} {self.workflow.pipeline_number}:{self.args.job_num} failed {cluster_text}:dumpster-fire:.
    (<{gh_url}|Issue in GitHub>)
    {self.render_success_stats(stats)}"""

    return text

  def render_success_stats(self, stats: str | SuccessStats):
    if isinstance(stats, str):
      return stats
    else:
      now = datetime.now(timezone.utc)
      return f"""{self.failure_stat(f"workflow {self.workflow.name}", stats.failed_workflows, stats.last_workflow_success, stats, now)}
    {self.failure_stat(f"Job {self.args.job_name}", stats.failed_jobs, stats.last_job_success, stats, now)}"""

  def failure_stat(self, what: str, failures: int, last_success: datetime | None, windows: SuccessStats, now: datetime):
    if failures:
      pretty_success = (f"Last success: {naturaldelta(now - last_success)} ago" if last_success
                        else f"No successes in {naturaldelta(windows.success_window)}")
      return f"{what} failed {failures} times in last {naturaldelta(windows.failure_window)} ({pretty_success})"
    else:
      return (f"Last {what} success: {naturaldelta(now - last_success)} ago" if last_success
              else f"No {what} success in last {naturaldelta(windows.success_window)}")


