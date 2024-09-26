#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import os
import re
import requests
from datetime import datetime, timezone, timedelta
import time
from dataclasses import field
from marshmallow_dataclass import dataclass
from marshmallow import EXCLUDE
import marshmallow.validate

MAX_TIMEOUT_MINUTES = 480
BASE_CCI_API_URL = "https://circleci.com/api/v2"
BRANCH=os.environ.get('CIRCLE_BRANCH')
PROJECT_USERNAME = os.environ.get('CIRCLE_PROJECT_USERNAME')
PROJECT_REPONAME = os.environ.get('CIRCLE_PROJECT_REPONAME')
TOKEN = os.environ.get('CIRCLECI_TOKEN')
if (not TOKEN):
  raise AttributeError("Missing CIRCLECI_TOKEN environment variable")
HEADERS = {"Circle-Token": TOKEN, "Content-Type": "application/json"}
START_TIME = datetime.now()
MAX_TIMEOUT=timedelta(hours=8)
SELF_WORKFLOW_ID = os.environ.get('CIRCLE_WORKFLOW_ID')

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

def str2bool(v):
    if isinstance(v, bool):
        return v
    if v.lower() in ('yes', 'true', 't', 'y', '1'):
        return True
    elif v.lower() in ('no', 'false', 'f', 'n', '0'):
        return False
    else:
        raise argparse.ArgumentTypeError('Boolean value expected.')

def parse_args():
  parser = argparse.ArgumentParser()
  parser.add_argument('--current_pipeline_number', required=True, type=int)
  parser.add_argument('--workflow_names', required=True)
  parser.add_argument('--branch_filter', required=False, default=BRANCH)
  parser.add_argument('--branch_filter_is_regex', required=False, default=False, type=str2bool)
  parser.add_argument('--branch_ignores', required=False, default="^$")
  parser.add_argument('--max_age_seconds', required=False, default=0, type=int)
  parser.add_argument('operation',
                      choices=[
                        'wait',
                        'wait_workflow',
                        'cancel_self',
                        'cancel_self_if_found',
                        'cancel_pipeline']
                        )

  args = parser.parse_args()
  if (args.branch_filter_is_regex and args.max_age_seconds == 0):
    parser.error("Must provide a non-zero value for MAX_AGE_SECONDS if using a regex as the branch filter")

  if (args.branch_filter == ""):
    args.branch_filter = BRANCH

  if (re.match(args.branch_ignores, BRANCH)):
    print("Skipping execution because branch is ignored")
    exit(0)

  print(f"Running with arguments: {args}")

  return args


def fetch_previous_pipelines(current_pipeline_number: int, branch_filter: str, branch_filter_is_regex: bool, max_age_seconds: int) -> list[Pipeline]:
  cutoff_date = (datetime.now() - timedelta(seconds=max_age_seconds)) if max_age_seconds > 0 else datetime.fromtimestamp(0)
  url = f"{BASE_CCI_API_URL}/project/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/pipeline"

  if branch_filter_is_regex:
    params = {}
  else:
    params = {"branch": branch_filter}

  pipelines: list[Pipeline] = []
  next_page_token = None

  while True:
    request_params = params | ({"page-token": next_page_token} if next_page_token else {})
    print(f"Request params: {request_params}")
    raw_response = requests.get(url, params = request_params, headers = HEADERS)
    raw_response.raise_for_status()
    response: PipelinesResponse = PipelinesResponse.Schema().load(raw_response.json())
    next_page_token = response.next_page_token
    pipelines += response.items
    earliest_date = min(response.items, key=lambda x: x.created_at).created_at
    print(f"Earliest date in page: {earliest_date}")
    print(f"Largest number in page: {max(response.items, key=lambda x: x.number).number}")
    print(f"Smallest number in page: {min(response.items, key=lambda x: x.number).number}")
    if earliest_date.timestamp() < cutoff_date.timestamp():
      print(f"Earliest date is older than cutoff date ({cutoff_date}), stopping")
      break
    if not next_page_token:
      break

  pipelines = [x for x in pipelines if x.created_at.timestamp() > cutoff_date.timestamp()]
  pipelines = [x for x in pipelines if x.number < current_pipeline_number]

  if branch_filter_is_regex:
    pipelines = [x for x in pipelines if x.vcs and x.vcs.branch and re.match(branch_filter, x.vcs.branch)]

  return pipelines

def fetch_workflows(pipeline_id: str) -> list[Workflow]:
  url = f"{BASE_CCI_API_URL}/pipeline/{pipeline_id}/workflow"
  workflows: list[Workflow] = []
  next_page_token = None
  while True:
    params = ({"page-token": next_page_token} if next_page_token else {})
    raw_response = requests.get(url, params = params, headers = HEADERS)
    raw_response.raise_for_status()
    response: WorkflowsResponse = WorkflowsResponse.Schema().load(raw_response.json())
    next_page_token = response.next_page_token
    workflows += response.items
    if not next_page_token:
      break

  return workflows

def pipeline_workflows_complete(pipeline: Pipeline):
  return all([workflow.is_complete() for workflow in fetch_workflows(pipeline.id)])

def wait_for_pipeline_to_complete(pipeline: Pipeline):
  while not pipeline_workflows_complete(pipeline):
    print(f"Pipeline {pipeline.number} still running. waiting...")
    time.sleep(5)
    if datetime.now() - START_TIME > MAX_TIMEOUT:
      raise TimeoutError(f"Timed out after {MAX_TIMEOUT}")

def workflow_complete(pipeline: Pipeline, workflow: Workflow):
  return [x for x in fetch_workflows(pipeline.id) if x.id == workflow.id][0].is_complete()

def wait_for_workflow_to_complete(pipeline: Pipeline, workflow: Workflow):
  while not workflow_complete(pipeline, workflow):
    print(f"Workflow {workflow.name} ({workflow.id}) in pipeline {pipeline.number} still running. waiting...")
    time.sleep(5)
    if datetime.now() - START_TIME > MAX_TIMEOUT:
      raise TimeoutError(f"Timed out after {MAX_TIMEOUT}")

def cancel_workflow(workflow_id: str):
  url = f"{BASE_CCI_API_URL}/workflow/{workflow_id}/cancel"
  raw_response = requests.post(url, headers = HEADERS)
  raw_response.raise_for_status()

def cancel_self_if_pipeline_running(pipeline: Pipeline):
  if not pipeline_workflows_complete(pipeline):
    cancel_workflow(SELF_WORKFLOW_ID)

def cancel_all_pipeline_workflows(pipeline: Pipeline):
  # Here we cancel all non-terminated workflows to simulate the behaviour of CCI with
  # "Auto-cancel redundant workflows" set (https://circleci.com/docs/skip-build/#auto-cancel)
  workflows = fetch_workflows(pipeline.id)
  for workflow in workflows:
    if workflow.status in ["running", "failing", "on_hold"]:
      print(f"Canceling workflow {workflow.name} ({workflow.id}), which has status {workflow.status}")
      cancel_workflow(workflow.id)

def main(args):
  print("** Fetching Pipelines **")
  pipelines = fetch_previous_pipelines(args.current_pipeline_number, args.branch_filter, args.branch_filter_is_regex, args.max_age_seconds)
  print(f"** Found {len(pipelines)} pipelines **")
  for pipeline in pipelines:
    pipeline_number = pipeline.number
    pipeline_id = pipeline.id
    ago = datetime.now(timezone.utc) - pipeline.created_at
    print(f"Pipeline {pipeline_number}, id {pipeline_id}, created at {pipeline.created_at} ({ago.total_seconds()} seconds ago)")

    workflows = fetch_workflows(pipeline_id)
    # print(f"Found {len(workflows)} workflows: {workflows}")
    for workflow in workflows:
      if not workflow.name in args.workflow_names.split(" "):
        # print(f"Skipping workflow {workflow.name}, not in workflow_names")
        continue
      print(f"Workflow {workflow.name}, id {workflow.id}, in workflow_names")

      match args.operation:
        case 'wait':
          print(f"Pipline {pipeline_number} contains workflow {workflow.name}, waiting for the pipeline to complete")
          wait_for_pipeline_to_complete(pipeline)
        case 'wait_workflow':
          print(f"Pipline {pipeline_number} contains workflow {workflow.name}, waiting for the workflow to complete")
          wait_for_workflow_to_complete(pipeline, workflow)
        case 'cancel_self':
          print(f"Pipeline contains workflow {workflow.name}, cancelling current workflow if pipeline is still running...")
          cancel_self_if_pipeline_running(pipeline)
        case 'cancel_self_if_found':
          print(f"Pipeline contains workflow {workflow.name}, cancelling current workflow (regardless of the found pipeline's status)")
          cancel_workflow(SELF_WORKFLOW_ID)
        case 'cancel_pipeline':
          print(f"Pipeline contains workflow {workflow.name}, cancelling all workflows for the pipeline")
          cancel_all_pipeline_workflows(pipeline)
        case _:
          raise Exception(f"Unexpected operation {args.operation}")

if __name__ == "__main__":
  args = parse_args()
  main(args)
