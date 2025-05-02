# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import json
import requests
import sys
import re
from abc import ABC, abstractmethod
from failure_notification_args import FailureArgs

class SlackNotification(ABC):
  @abstractmethod
  def build_msg(self, gh_url: str):
    pass

  def send_notification(self, gh_url: str):

    if not re.match(self.args.branch_pattern, self.args.branch):
      print(f"Branch {self.args.branch} does not match pattern {self.args.branch_pattern}, skipping notification")
      sys.exit(0)

    text = self.build_msg(gh_url)
    msg = {"text": text, "channel": self.args.slack_channel}

    print(f"Notification: {json.dumps(msg)}")

    if not self.args.dry_run:
      requests.post(
        "https://slack.com/api/chat.postMessage",
        json=msg,
        headers={
          "Authorization": f"Bearer {os.environ.get('SLACK_ACCESS_TOKEN')}",
          "Content-Type": "application/json"})


  def __init__(self, args: FailureArgs):
    self.args = args


