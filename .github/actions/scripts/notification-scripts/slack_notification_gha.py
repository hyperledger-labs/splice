# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from slack_notification import *

class GHASlackNotification(SlackNotification):

  def build_msg(self, gh_url: str):
    cluster_text = f" on cluster {self.args.cluster}" if self.args.cluster else ""
    text=f"""*GHA job {self.args.gha_workflow_name}:{self.args.job_name} {self.args.gha_run_id} failed {cluster_text}:dumpster-fire:.
    (<{gh_url}|Issue in GitHub>)"""

    return text
