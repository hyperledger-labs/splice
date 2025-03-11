# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from collections.abc import Iterator
from datetime import datetime, timezone, timedelta
import time
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
  created_at: datetime
  stopped_at: datetime | None
  class Meta:
    unknown = EXCLUDE

  def is_complete(self):
    return self.status not in ["running", "failing"]

@dataclass
class InsightWorkflow:
  id: str
  status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "not_running", "failed", "error", "failing", "on_hold", "canceled", "unauthorized"])})
  stopped_at: datetime | None
  class Meta:
    unknown = EXCLUDE

@dataclass
class WorkflowsResponse:
  next_page_token: str | None
  items: list[Workflow]
  class Meta:
    unknown = EXCLUDE

@dataclass
class InsightWorkflowsResponse:
  next_page_token: str | None
  items: list[InsightWorkflow]
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

def gen_fetch_paginated(url: str, schema: Schema, params = {}, halt = lambda x: False) -> Iterator:
  items = []
  next_page_token = None
  tries, max_tries = 0, 5
  retry_delay = 2  # secs
  timeout = 3  # secs
  while True:
    request_params = params | ({"page-token": next_page_token} if next_page_token else {})
    try:
        raw_response = requests.get(url, params = request_params, headers = HEADERS, timeout = timeout)
    except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
        print(f"Attempt {tries}: Request to {url} with params {params} failed due to {e}")
        tries += 1
        if tries < max_tries:
            time.sleep(retry_delay)
            continue
        else:
            raise RuntimeError(f"Max attempts {max_tries} exceeded") from e
    raw_response.raise_for_status()
    json_data=raw_response.json()
    try:
      response = schema.load(json_data)
    except Exception as e:
      print(f"Failed to parse response from {url}: {json_data}")
      raise e
    next_page_token = response.next_page_token
    items += response.items
    for item in response.items:
      yield item
    if not next_page_token:
      break
    if halt(items):
      break

def fetch_paginated(url: str, schema: Schema, params = {}, halt = lambda x: False) -> list:
  return list(gen_fetch_paginated(url, schema, params, halt))

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

def _handleHttpErrorResponse(err: requests.exceptions.HTTPError):
  if err.response and err.response.status_code == 404:
    print(f"Attempt {attempt}/{max_retries}: Workflow not found for pipeline {pipeline_id}.")
  elif err.response:
    print(f"HTTP error (status code {err.response.status_code}): {err}")
  else:
    print(f"Request failed with no response: {err}")

def fetch_workflows(pipeline_id: str, max_retries=3, delay=3) -> list[Workflow]:
  url = f"{BASE_CCI_API_URL}/pipeline/{pipeline_id}/workflow"
  for attempt in range(1, max_retries + 1):
    try:
      return fetch_paginated(url, WorkflowsResponse.Schema())
    except requests.exceptions.HTTPError as err:
      _handleHttpErrorResponse(err)
    if attempt < max_retries:
      print(f"Retrying in {delay} seconds.")
      time.sleep(delay)
  print(f"Max retries reached. Unable to fetch workflows for pipeline {pipeline_id}.")
  return list()

def fetch_workflow(workflow_id: str, max_retries=3, delay=3) -> Workflow:
  url = f"{BASE_CCI_API_URL}/workflow/{workflow_id}"
  for attempt in range(1, max_retries + 1):
    try:
      raw_response = requests.get(url, headers = HEADERS)
      raw_response.raise_for_status()
      return Workflow.Schema().load(raw_response.json())
    except requests.exceptions.HTTPError as err:
      _handleHttpErrorResponse(err)
    if attempt < max_retries:
      print(f"Retrying in {delay} seconds.")
      time.sleep(delay)
  print(f"Max retries reached. Unable to fetch workflows for pipeline {pipeline_id}.")
  return None

def gen_recent_workflow_runs(workflow_name: str, branch: str, start_date: datetime, end_date: datetime) -> Iterator[InsightWorkflow]:
  url = f"{BASE_CCI_API_URL}/insights/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/workflows/{workflow_name}"
  for wf in gen_fetch_paginated(url, InsightWorkflowsResponse.Schema(),
                                {"branch": branch, "start-date": start_date, "end-date": end_date}):
    # end-date is based on start time; API also delivers workflows prior to start-date
    if start_date <= wf.stopped_at < end_date:
      yield wf

def cancel_workflow(workflow_id: str):
  url = f"{BASE_CCI_API_URL}/workflow/{workflow_id}/cancel"
  raw_response = requests.post(url, headers = HEADERS)
  raw_response.raise_for_status()

def fetch_jobs(workflow: Workflow) -> list[Job]:
  url = f"{BASE_CCI_API_URL}/workflow/{workflow.id}/job"
  return fetch_paginated(url, JobsResponse.Schema())

def _ratelimit_info(err: requests.exceptions.HTTPError):
  h = err.response.headers
  new_reset = h.get('RateLimit-Reset')
  old_reset = h.get('X-RateLimit-Reset')
  try:
    new_reset = int(new_reset or '')
  except ValueError:
    pass
  try:
    old_reset = int(old_reset or '')
  except ValueError:
    pass
  if isinstance(new_reset, int) and isinstance(old_reset, int):
    return f"{max(new_reset, old_reset)} seconds, Retry-After: {h.get('Retry-After')}"
  else:
    return f"RateLimit-Reset: {new_reset}, X-RateLimit-Reset: {old_reset}, Retry-After: {h.get('Retry-After')}"

@dataclass
class SuccessStats:
  failure_window: timedelta
  failed_jobs: int
  failed_workflows: int
  success_window: timedelta
  last_job_success: datetime | None
  last_workflow_success: datetime | None

def _try_failures_and_last_success(pipeline_number: int, branch: str, workflow: Workflow, job_name: str):
  prior_to_workflow_start = workflow.created_at - timedelta(seconds=1)
  failure_window = timedelta(hours=12)
  failed_statuses = frozenset(("failed", "error", "failing"))
  failed_workflows = [wf for wf in gen_recent_workflow_runs(workflow.name, branch, prior_to_workflow_start - failure_window, prior_to_workflow_start)
                      if wf.status in failed_statuses]
  failed_jobs = [j for wf in failed_workflows
                 for j in fetch_jobs(wf)
                 if j.name == job_name and j.status in failed_statuses]
  success_window = timedelta(days=30)
  last_success_wf = None
  last_success_job = None
  for wf in gen_recent_workflow_runs(workflow.name, branch, prior_to_workflow_start - success_window, prior_to_workflow_start):
    if not last_success_job:
      job_in_wf = [j for j in fetch_jobs(wf) if j.name == job_name]
      if job_in_wf and job_in_wf[0].status == 'success':
        last_success_job = job_in_wf[0]
    if not last_success_wf and wf.status == 'success':
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

# Fetch statistics on similar past jobs.  Note this takes 5sec to run in local
# tests, so consider passing around the results rather than re-calling
def failures_and_last_success(pipeline_number: int, branch: str, workflow: Workflow, job_name: str):
  try:
    return _try_failures_and_last_success(pipeline_number, branch, workflow, job_name)
  except requests.exceptions.HTTPError as e:
    if e.response.status_code == 429:
      return f"Hit a rate limit in CCI when fetching stats: {_ratelimit_info(e)}"
    else:
      raise e
