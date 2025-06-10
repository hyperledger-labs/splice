#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from github import Github
from github import Auth
import os

repos = ["DACH-NY/temp-test-splice-public", "DACH-NY/temp-test-cn-internal"]

# on purpose using GH_TOKEN instead of GITHUB_TOKEN, I locally set the latter to the service account for migrate-github-issues,
# but prefer to run the delete script with my personal token to not exhaust the rate limit on the SA
if os.environ.get("GH_TOKEN") is None:
    raise ValueError("GH_TOKEN environment variable is not set.")
auth = Auth.Token(os.environ["GH_TOKEN"])
github = Github(auth=auth)
for repo_name in repos:
  repo = github.get_repo(repo_name)
  for milestone in repo.get_milestones(state="all"):
    print(f"Deleting milestone {milestone.title} in {repo_name}")
    milestone.delete()
  for issue in repo.get_issues(state="open"):
    print(f"Closing issue {issue.title} in {repo_name}")
    issue.edit(state="closed")


