# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import os
from dataclasses import dataclass

@dataclass
class FailureArgs:
  cluster: str
  slack_channel: str
  branch_pattern: str
  dry_run: bool
  branch: str
  github_token: str
  job_subname: str
  # cci specific
  pipeline_id: str = ""
  workflow_id: str = ""
  job_num: str = ""
  job_name: str = ""
  parallel_run_idx: str = ""
  # gha specific
  gha_run_id: str = ""
  gha_workflow_name: str = ""


def parse_args() -> FailureArgs:
  parser = argparse.ArgumentParser()
  parser.add_argument('--cluster', default="")
  # The default channel is #team-canton-network-internal-ci
  parser.add_argument('--slack_channel', default="C05DT77QF5M")
  parser.add_argument('--branch_pattern', default=".*")
  parser.add_argument('--dry_run', action='store_true')
  parser.add_argument('--github_token', default=os.environ.get('GH_TOKEN'))

  if os.environ.get('GITHUB_ACTION'):
    parser.add_argument('--gha_run_id', default=os.environ.get('GITHUB_RUN_ID'))
    parser.add_argument('--gha_workflow_name', default=os.environ.get('GITHUB_WORKFLOW'))
    parser.add_argument('--job_name', default=os.environ.get('GITHUB_JOB'))
    parser.add_argument('--job_subname', default='')
    parser.add_argument('--branch', default=os.environ.get('GITHUB_HEAD_REF') or os.environ.get('GITHUB_REF'))
  else:
    parser.add_argument('--pipeline_id', default=os.environ.get('CIRCLE_PIPELINE_ID'))
    parser.add_argument('--workflow_id', default=os.environ.get('CIRCLE_WORKFLOW_ID'))
    parser.add_argument('--job_num', default=os.environ.get('CIRCLE_BUILD_NUM'))
    parser.add_argument('--job_name', default=os.environ.get('CIRCLE_JOB'))
    parser.add_argument('--parallel_run_idx', default=os.environ.get('CIRCLE_NODE_INDEX'))
    parser.add_argument('--branch', default=os.environ.get('CIRCLE_BRANCH'))
  return FailureArgs(**vars(parser.parse_args()))
