# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from failure_github_issue import *
from git import Repo
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from circleci import *

class CCIFailureGithubIssue(FailureGithubIssue):

  def __init__(self, args: FailureArgs):
    self.workflow = fetch_workflow(args.workflow_id)
    super().__init__(args)

  def get_msg(self) -> tuple[str, str]:
    circleci_url=f"https://app.circleci.com/pipelines/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/{self.workflow.pipeline_number}/workflows/{self.args.workflow_id}/jobs/{self.args.job_num}/parallel-runs/{self.args.parallel_run_idx}"
    branch_url=f"https://github.com/{PROJECT_USERNAME}/{PROJECT_REPONAME}/tree/{self.args.branch}"
    github_url=f"https://github.com/{PROJECT_USERNAME}/{PROJECT_REPONAME}/commit/"
    repo = Repo(search_parent_directories=True)
    commit_msg = repo.head.commit.summary
    commit_author = repo.head.commit.author.name
    commit_sha = repo.head.object.hexsha
    commit_sha_short = commit_sha[:7]

    title = f"Pipeline {self.workflow.pipeline_number} : workflow {self.args.gha_workflow_name} Failed :fire:"
    body = f"""
[CircleCI Job]({circleci_url}).
Branch: [{self.args.branch}]({branch_url})
Workflow: {self.workflow.name}
Commit: [{commit_sha_short}]({github_url}) {commit_msg}
Author: {commit_author}
"""

    return (title, body)

  def get_workflow_name(self) -> str:
    return self.workflow.name
