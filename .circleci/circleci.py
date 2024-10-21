# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from datetime import datetime, timezone, timedelta
from itertools import chain
import os
import re
import requests

from dataclasses import field
from marshmallow_dataclass import dataclass
from marshmallow import EXCLUDE, Schema
import marshmallow.validate

BASE_CCI_API_URL = "https://circleci.com/api/v2"
PROJECT_USERNAME = os.environ.get('CIRCLE_PROJECT_USERNAME')
PROJECT_REPONAME = os.environ.get('CIRCLE_PROJECT_REPONAME')
TOKEN = os.environ.get('CIRCLECI_TOKEN')
if (not TOKEN):
  raise AttributeError("Missing CIRCLECI_TOKEN environment variable")
HEADERS = {"Circle-Token": TOKEN, "Content-Type": "application/json"}

@dataclass
class VCS:
  branch: str | None
  class Meta:
    unknown = EXCLUDE

@dataclass
class Pipeline:
  id: str
  number: int
  created_at: datetime
  vcs: VCS | None
  class Meta:
    unknown = EXCLUDE

@dataclass
class PipelinesResponse:
  next_page_token: str | None
  items: list[Pipeline]
  class Meta:
    unknown = EXCLUDE

@dataclass
class Workflow:
  id: str
  name: str
  pipeline_number: int
  # unfun fact: circleci docs says "not_run", but we've seen "not_running" in the wild, so we defensively allow both
  status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "not_running", "failed", "error", "failing", "on_hold", "canceled", "unauthorized"])})
  stopped_at: datetime | None
  class Meta:
    unknown = EXCLUDE

  def is_complete(self):
    return self.status not in ["running", "failing"]

@dataclass
class WorkflowsResponse:
  next_page_token: str | None
  items: list[Workflow]
  class Meta:
    unknown = EXCLUDE

@dataclass
class Job:
  id: str
  name: str
  # unfun fact: circleci docs says "not_run", but we've seen "not_running" in the wild, so we defensively allow both
  status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "not_running", "failed", "error", "failing", "on_hold", "canceled", "unauthorized", "blocked"])})
  stopped_at: datetime | None
  class Meta:
    unknown = EXCLUDE

@dataclass
class JobsResponse:
  next_page_token: str | None
  items: list[Job]
  class Meta:
    unknown = EXCLUDE

def fetch_paginated(url: str, schema: Schema, params = {}, halt = lambda x: False) -> list:
  items = []
  next_page_token = None
  while True:
    request_params = params | ({"page-token": next_page_token} if next_page_token else {})
    raw_response = requests.get(url, params = request_params, headers = HEADERS)
    raw_response.raise_for_status()
    json_data=raw_response.json()
    try:
      response = schema.load(json_data)
    except Exception as e:
      print(f"Failed to parse response from {url}: {json_data}")
      raise e
    next_page_token = response.next_page_token
    items += response.items
    if not next_page_token:
      break
    if halt(items):
      break
  return items

def fetch_previous_pipelines(current_pipeline_number: int, branch_filter: str, branch_filter_is_regex: bool, max_age_seconds: int) -> list[Pipeline]:
  cutoff_date = (datetime.now(timezone.utc) - timedelta(seconds=max_age_seconds)) if max_age_seconds > 0 else datetime.fromtimestamp(0)
  url = f"{BASE_CCI_API_URL}/project/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/pipeline"

  if branch_filter_is_regex:
    params = {}
  else:
    params = {"branch": branch_filter}

  pipelines = fetch_paginated(
    url,
    PipelinesResponse.Schema(),
    params,
    lambda items: min(items, key=lambda x: x.created_at).created_at.timestamp() < cutoff_date.timestamp())

  pipelines = [x for x in pipelines if x.created_at.timestamp() > cutoff_date.timestamp()]
  pipelines = [x for x in pipelines if x.number < current_pipeline_number]

  if branch_filter_is_regex:
    pipelines = [x for x in pipelines if x.vcs and x.vcs.branch and re.match(branch_filter, x.vcs.branch)]

  return pipelines

def fetch_workflows(pipeline_id: str) -> list[Workflow]:
  url = f"{BASE_CCI_API_URL}/pipeline/{pipeline_id}/workflow"
  return fetch_paginated(url, WorkflowsResponse.Schema())

def fetch_workflow(workflow_id: str) -> Workflow:
  url = f"{BASE_CCI_API_URL}/workflow/{workflow_id}"
  raw_response = requests.get(url, headers = HEADERS)
  raw_response.raise_for_status()
  return Workflow.Schema().load(raw_response.json())

def cancel_workflow(workflow_id: str):
  url = f"{BASE_CCI_API_URL}/workflow/{workflow_id}/cancel"
  raw_response = requests.post(url, headers = HEADERS)
  raw_response.raise_for_status()

def fetch_jobs(workflow: Workflow) -> list[Job]:
  url = f"{BASE_CCI_API_URL}/workflow/{workflow.id}/job"
  return fetch_paginated(url, JobsResponse.Schema())

@dataclass
class SuccessStats:
  failure_window: timedelta
  failed_jobs: int
  failed_workflows: int
  success_window: timedelta
  last_job_success: datetime | None
  last_workflow_success: datetime | None

# Fetch statistics on similar past jobs.  Note this takes 5sec to run in local
# tests, so consider passing around the results rather than re-calling
def failures_and_last_success(pipeline_number: int, branch: str, workflow: Workflow, job_name: str):
  failure_window = timedelta(hours=12)
  window_pipelines = fetch_previous_pipelines(pipeline_number, branch, False, failure_window.total_seconds())
  failed_statuses = frozenset(("failed", "error", "failing"))
  window_workflows = [wf for p in window_pipelines
                      for wf in fetch_workflows(p.id)
                      if wf.name == workflow.name]
  failed_workflows = [wf for wf in window_workflows
                      if wf.status in failed_statuses]
  failed_jobs = [j for wf in failed_workflows
                 for j in fetch_jobs(wf)
                 if j.name == job_name and j.status in failed_statuses]
  success_window = timedelta(days=30)
  assert success_window > failure_window
  # this assumes that the last pipeline happened about failure_window ago,
  # which isn't quite right but probably close enough
  (past_pipeline, past_window) = ((window_pipelines[-1].number, success_window - failure_window)
    if window_pipelines else (pipeline_number, success_window))
  last_success_wf = None
  last_success_job = None
  # fetch more workflows to search for success only if there isn't a success
  # in the failure window
  for wf in chain(window_workflows,
                  (wf
                   for p in fetch_previous_pipelines(past_pipeline, branch, False, past_window.total_seconds())
                   for wf in fetch_workflows(p.id)
                   if wf.name == workflow.name)):
    job_in_wf = [j for j in fetch_jobs(wf) if j.name == job_name]
    if job_in_wf and job_in_wf[0].status == 'success':
      last_success_job = job_in_wf[0]
    if wf.status == 'success':
      last_success_wf = wf
    if last_success_wf and last_success_job:
      break
  return SuccessStats(
    failure_window = failure_window,
    failed_jobs = len(failed_jobs),
    failed_workflows = len(failed_workflows),
    success_window = success_window,
    last_job_success = last_success_job and last_success_job.stopped_at,
    last_workflow_success = last_success_wf and last_success_wf.stopped_at)
