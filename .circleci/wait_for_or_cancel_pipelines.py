#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import re
import time
from datetime import datetime, timedelta, timezone

from circleci import *

START_TIME = datetime.now()
MAX_TIMEOUT = timedelta(hours=8)
SELF_WORKFLOW_ID = os.environ.get('CIRCLE_WORKFLOW_ID')
CURRENT_BRANCH = os.environ.get('CIRCLE_BRANCH')
IGNORE_PIPELINES = os.environ.get('CIRCLECI_IGNORE_PIPELINES', '')
IGNORE_PIPELINES_LIST = IGNORE_PIPELINES.split(',')


@dataclass
class Job:
    id: str
    name: str
    status: str = field(metadata={"validate": marshmallow.validate.OneOf(["running", "success", "not_run", "not_running", "failed", "error", "failing", "on_hold", "canceled", "unauthorized", "blocked"])})

    class Meta:
        unknown = EXCLUDE


@dataclass
class JobsResponse:
    next_page_token: str | None
    items: list[Job]

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
    parser.add_argument('--branch_filter', required=False, default=CURRENT_BRANCH)
    parser.add_argument('--branch_filter_is_regex', required=False, default=False, type=str2bool)
    parser.add_argument('--branch_ignores', required=False, default="^$")
    parser.add_argument('--max_age_seconds', required=False, default=0, type=int)
    parser.add_argument('--extra_seconds_after_finish', required=False, default=0, type=int)
    parser.add_argument('--waiting_job_name', required=False, default="wait_for_previous_pipeline")
    parser.add_argument('operation',
                        choices=[
                            'wait',
                            'wait_workflow',
                            'cancel_waiting_and_wait_workflow',
                            'cancel_self',
                            'cancel_pipeline',
                            'cancel_self_if_pipeline_failed']
                        )

    args = parser.parse_args()
    if (args.branch_filter_is_regex and args.max_age_seconds == 0):
        parser.error("Must provide a non-zero value for MAX_AGE_SECONDS if using a regex as the branch filter")

    if (args.branch_filter == ""):
        args.branch_filter = CURRENT_BRANCH

    if (re.match(args.branch_ignores, CURRENT_BRANCH)):
        print("Skipping execution because branch is ignored")
        exit(0)

    print(f"Running with arguments: {args}")

    return args


def pipeline_workflows_complete(pipeline: Pipeline):
    print(f"Checking if pipeline {pipeline.number} is complete")
    return all([workflow.is_complete() for workflow in fetch_workflows(pipeline.id)])


def pipeline_workflows_complete_successfully(pipeline: Pipeline):
    """
    Checks if the argument pipeline has completed successfully.
    :param pipeline: The pipeline to check for completion.
    """
    wait_for_pipeline_to_complete(pipeline)
    return all([workflow.is_complete() and workflow.status == "success" for workflow in fetch_workflows(pipeline.id)])


def wait_for_pipeline_to_complete(pipeline: Pipeline):
    print(f"Waiting for pipeline {pipeline.number} to complete")
    while not pipeline_workflows_complete(pipeline):
        print(f"Pipeline {pipeline.number} still running. waiting...")
        time.sleep(5)
        if datetime.now() - START_TIME > MAX_TIMEOUT:
            raise TimeoutError(f"Timed out after {MAX_TIMEOUT}")


def cancel_if_waiting_or_wait_workflow(pipeline: Pipeline, workflow: Workflow, waiting_job_name: str):
    jobs = fetch_jobs(workflow)
    status = [x for x in jobs if x.name == waiting_job_name][0].status
    if status == "running" or status == "not_run" or status == "not_running":
        print(f"Workflow {workflow.name} ({workflow.id}) is waiting (the waiting job is in status {status}), cancelling it")
        cancel_workflow(workflow.id)
    else:
        print(f"Workflow {workflow.name} ({workflow.id}) is already running, waiting for it to complete")
        wait_for_workflow_to_complete(pipeline, workflow)


def workflow_complete(pipeline: Pipeline, workflow: Workflow):
    return [x for x in fetch_workflows(pipeline.id) if x.id == workflow.id][0].is_complete()


def wait_for_workflow_to_complete(pipeline: Pipeline, workflow: Workflow):
    while not workflow_complete(pipeline, workflow):
        print(f"Workflow {workflow.name} ({workflow.id}) in pipeline {pipeline.number} still running. waiting...")
        time.sleep(5)
        if datetime.now() - START_TIME > MAX_TIMEOUT:
            raise TimeoutError(f"Timed out after {MAX_TIMEOUT}")


def cancel_self_if_pipeline_running(pipeline: Pipeline) -> bool:
    if not pipeline_workflows_complete(pipeline):
        cancel_workflow(SELF_WORKFLOW_ID)
        return True
    return False


def cancel_self_if_pipeline_failed(pipeline: Pipeline) -> bool:
    """
    Cancels execution of current workflow if the argument pipeline has not completed successfully.
    :param pipeline: The pipeline to check for completion.
    """
    if not pipeline_workflows_complete_successfully(pipeline):
        print(f"Pipeline {pipeline.number} is complete, but failed. Cancelling self workflow")
        cancel_workflow(SELF_WORKFLOW_ID)
    else:
        print(f"Pipeline {pipeline.number} is complete, and successful. Not cancelling self workflow")


def cancel_self_if_in_period_after_finish(pipeline: Pipeline):
    cancelled_because_still_running = cancel_self_if_pipeline_running(pipeline)
    if not cancelled_because_still_running:
        workflows = fetch_workflows(pipeline.id)
        # all workflows are guaranteed to be complete because cancel_self_if_pipeline_running was called
        cancel_after = datetime.now(timezone.utc) - timedelta(seconds=args.extra_seconds_after_finish)
        for workflow in workflows:
            if workflow.stopped_at is None:
                print(f"Workflow has no stopped_at, but should already be complete! {workflow}")
            elif workflow.stopped_at > cancel_after:
                print(f"Workflow {workflow.name} ({workflow.id}) was stopped at {workflow.stopped_at}, which is after {cancel_after}. Cancelling self workflow...")
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
        if pipeline_id in IGNORE_PIPELINES_LIST:
            print(f"Skipping pipeline {pipeline_id} as it is in the ignore list.")
            continue

        workflows = fetch_workflows(pipeline_id)

        for workflow in workflows:
            if not workflow.name in args.workflow_names.split(" "):
                continue
            print(f"Workflow {workflow.name}, id {workflow.id}, in workflow_names")

            match args.operation:
                case 'wait':
                    print(f"Pipeline {pipeline_number} contains workflow {workflow.name}, waiting for the pipeline to complete")
                    wait_for_pipeline_to_complete(pipeline)
                case 'wait_workflow':
                    print(f"Pipeline {pipeline_number} contains workflow {workflow.name}, waiting for the workflow to complete")
                    wait_for_workflow_to_complete(pipeline, workflow)
                case 'cancel_waiting_and_wait_workflow':
                    print(f"Pipeline {pipeline_number} contains workflow {workflow.name}, checking if it is also waiting, or already running")
                    cancel_if_waiting_or_wait_workflow(pipeline, workflow, args.waiting_job_name)
                case 'cancel_self':
                    print(f"Pipeline contains workflow {workflow.name}, cancelling current workflow if pipeline is still running or was finished less than {args.extra_seconds_after_finish} seconds ago")
                    cancel_self_if_in_period_after_finish(pipeline)
                case 'cancel_pipeline':
                    print(f"Pipeline contains workflow {workflow.name}, cancelling all workflows for the pipeline")
                    cancel_all_pipeline_workflows(pipeline)
                case 'cancel_self_if_pipeline_failed':
                    print(f"Pipeline {pipeline_number} contains workflow {workflow.name}, checking it completed successfully")
                    cancel_self_if_pipeline_failed(pipeline)
                case _:
                    raise Exception(f"Unexpected operation {args.operation}")


if __name__ == "__main__":
    args = parse_args()
    main(args)
