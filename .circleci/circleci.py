# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from datetime import datetime, timezone, timedelta
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
  status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "failed", "error", "failing", "on_hold", "canceled", "unauthorized"])})
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
  status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "failed", "error", "failing", "on_hold", "canceled", "unauthorized", "blocked"])})
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
    response = schema.load(raw_response.json())
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

