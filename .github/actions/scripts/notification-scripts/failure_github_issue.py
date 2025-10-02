#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from failure_notification_args import FailureArgs
import os
from git import Repo

GH_ORGANIZATION="DACH-NY"
GH_REPO="cn-test-failures"
GH_FAILURES_PROJECT=48

class FailureGithubIssue():

  def get_msg(self) -> tuple[str, str]:
    GITHUB_SERVER_URL=os.environ.get('GITHUB_SERVER_URL')
    GITHUB_REPOSITORY=os.environ.get('GITHUB_REPOSITORY')
    GIT_SHA=os.environ.get('GITHUB_SHA')
    branch_url=f"{GITHUB_SERVER_URL}/{GITHUB_REPOSITORY}/tree/{self.args.branch}"
    github_url=f"{GITHUB_SERVER_URL}/{GITHUB_REPOSITORY}/commit/{GIT_SHA}"
    # Unfortunately, github does not provide a way to get the job number, s.a. https://github.com/orgs/community/discussions/129314
    # so we can only link to the run, not directly to the failed job
    job_url=f"{GITHUB_SERVER_URL}/{GITHUB_REPOSITORY}/actions/runs/{self.args.gha_run_id}"
    repo = Repo(search_parent_directories=True)
    commit_msg = repo.head.commit.summary
    commit_sha_short = GIT_SHA[:7]
    job_descriptor = (f"{self.args.job_subname} in " if self.args.job_subname else '') + self.args.job_name

    title = f"GHA Run {self.args.gha_run_id} : job {job_descriptor} Failed :fire:"
    body = f"""
[GitHub Actions Run]({job_url}).
Job: {job_descriptor}
Branch: [{self.args.branch}]({branch_url})
Workflow: {self.args.gha_workflow_name}
Commit: [{commit_sha_short}]({github_url}) {commit_msg}
Actor: {os.environ.get('GITHUB_ACTOR')}
"""

    return (title, body)

  def __init__(self, args: FailureArgs):
    self.args = args
