#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import os
import json
from git import Repo
import requests

from circleci import *

FAILED_PIPELINE_ID = os.environ.get('CIRCLE_PIPELINE_ID')
FAILED_WORKFLOW_ID = os.environ.get('CIRCLE_WORKFLOW_ID')
FAILED_JOB_NUM = os.environ.get('CIRCLE_BUILD_NUM')
FAILED_JOB_NAME = os.environ.get('CIRCLE_JOB')
FAILED_PARALLEL_RUN_IDX = os.environ.get('CIRCLE_NODE_INDEX')
CURRENT_BRANCH = os.environ.get('CIRCLE_BRANCH')

def parse_args():
  parser = argparse.ArgumentParser()
  parser.add_argument('--cluster', required=True)
  # The default channel is #team-canton-network-internal-ci
  parser.add_argument('--slack_channel', default="C05DT77QF5M")
  parser.add_argument('--branch_pattern', default=".*")
  parser.add_argument('--dry_run', action='store_true')
  return parser.parse_args()

def build_msg():
  workflow = fetch_workflow(FAILED_WORKFLOW_ID)
  circleci_url=f"https://app.circleci.com/pipelines/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/{workflow.pipeline_number}/workflows/{FAILED_WORKFLOW_ID}/jobs/{FAILED_JOB_NUM}/parallel-runs/{FAILED_PARALLEL_RUN_IDX}"

  repo = Repo(search_parent_directories=True)
  commit_msg = repo.head.commit.summary
  commit_author = repo.head.commit.author.name
  commit_sha = repo.head.object.hexsha
  commit_sha_short = commit_sha[:7]

  github_url = f"https://github.com/{PROJECT_USERNAME}/{PROJECT_REPONAME}/commit/{commit_sha}"

  text=f"""*Job {workflow.name}:{FAILED_JOB_NAME} failed on cluster {args.cluster}* :dumpster-fire:.
  *{CURRENT_BRANCH}*: <{circleci_url}|{commit_sha_short} {commit_msg}> ({commit_author} <{github_url}|GitHub>)
  (<https://github.com/orgs/DACH-NY/projects/48|See GH dashboard>)"""

  return text

def main(args):

  if (not re.match(args.branch_pattern, CURRENT_BRANCH)):
    print(f"Branch {CURRENT_BRANCH} does not match pattern {args.branch_pattern}, skipping notification")
    exit(0)

  text = build_msg()
  msg = {"text": text, "channel": args.slack_channel}

  print(f"Notification: {json.dumps(msg)}")

  if not args.dry_run:
    requests.post(
      "https://slack.com/api/chat.postMessage",
      json=msg,
      headers={
        "Authorization": f"Bearer {os.environ.get('SLACK_ACCESS_TOKEN')}",
        "Content-Type": "application/json"})

if __name__ == "__main__":
  args = parse_args()
  main(args)
