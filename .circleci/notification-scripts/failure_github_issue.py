#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
from git import Repo
from gql import gql, Client
from gql.transport.requests import RequestsHTTPTransport
import sys

sys.path.append('../')
from circleci import *
from failure_notification_args import FailureArgs

GH_TOKEN = os.environ.get('GITHUB_TOKEN')
GH_ORGANIZATION="DACH-NY"
GH_REPO="cn-test-failures"
GH_FAILURES_PROJECT=48

def get_repo_id(client: Client, organization: str, repo_name: str) -> str:
  query = gql(
  """
  query getRepoId($owner: String!, $name: String!) {
    repository(owner: $owner, name: $name) {
      id
    }
  }
  """
  )
  params = {
    "owner": organization,
    "name": repo_name
  }
  response = client.execute(query, params)
  return response["repository"]["id"]

def get_project_id(client: Client, organization: str, project_number: str) -> str:
  query = gql(
  """
    query getProjectId($organization: String!, $projectNumber: Int!) {
      organization(login: $organization) {
        projectV2(number: $projectNumber) {
          id
        }
      }
    }
  """
  )
  params = {
    "organization": organization,
    "projectNumber": project_number
  }
  response = client.execute(query, params)
  return response["organization"]["projectV2"]["id"]

def create_issue(client: Client, repo_id: str, title: str, body: str) -> str:
  query = gql(
  """
  mutation createIssue($repositoryId: ID!, $title: String!, $body: String!) {
    createIssue(input: {
      repositoryId: $repositoryId,
      title: $title,
      body: $body
    }) {
      issue {
        id
      }
    }
  }
  """
  )
  params = {
    "repositoryId": repo_id,
    "title": title,
    "body": body
  }
  response = client.execute(query, params)
  return response["createIssue"]["issue"]["id"]

def add_issue_to_project(client: Client, project_id: str, issue_id: str) -> str:
  query = gql(
  """
    mutation addToProject($projectId: ID!, $contentId: ID!) {
      addProjectV2ItemById(input: {
        projectId: $projectId,
        contentId: $contentId
      }) { item { id } }
    }
  """
  )
  params = {
    "projectId": project_id,
    "contentId": issue_id
  }
  response = client.execute(query, params)
  return response["addProjectV2ItemById"]["item"]["id"]

def get_issue_number(client: Client, issue_id: str) -> str:
  query = gql(
  """
  query getIssueNumber($issueId: ID!) {
    node(id: $issueId) {
      ... on Issue {
        number
      }
    }
  }
  """
  )
  params = {
    "issueId": issue_id
  }
  response = client.execute(query, params)
  return response["node"]["number"]

class ProjectFields:
  def __init__(self, data: list[dict[str, str]]):
    self.data = data

  def get_field_id(self, name: str) -> str:
    return [f for f in self.data if f["name"] == name][0]["id"]

def get_project_fields(client: Client, project_id: str) -> ProjectFields:
  query = gql(
  """
    query getFields($projectId: ID!) {
      node(id: $projectId) {
        ... on ProjectV2 {
          fields(first: 20) {
            nodes {
              ... on ProjectV2FieldCommon {
                id
                name
              }
            }
          }
        }
      }
    }
  """
  )
  params = {
    "projectId": project_id
  }
  response = client.execute(query, params)
  return ProjectFields(response["node"]["fields"]["nodes"])

def set_field_value(client: Client, project_id: str, item_id: str, field_id: str, value: str) -> str:
  query = gql(
  """
  mutation setProjectItemValue($projectId: ID!, $itemId: ID!, $fieldId: ID!, $value: String!) {
    updateProjectV2ItemFieldValue(
      input: {
        projectId: $projectId
        itemId: $itemId
        fieldId: $fieldId
        value: {
          text: $value
        }
      }
    )
    {
      projectV2Item {
        id
      }
    }
  }

  """
  )

  params = {
    "projectId": project_id,
    "itemId": item_id,
    "fieldId": field_id,
    "value": value
  }
  response = client.execute(query, params)
  return response["updateProjectV2ItemFieldValue"]["projectV2Item"]["id"]

def get_msg(args: FailureArgs, workflow: Workflow) -> tuple[str, str]:
  circleci_url=f"https://app.circleci.com/pipelines/github/{PROJECT_USERNAME}/{PROJECT_REPONAME}/{workflow.pipeline_number}/workflows/{args.workflow_id}/jobs/{args.job_num}/parallel-runs/{args.parallel_run_idx}"
  branch_url=f"https://github.com/{PROJECT_USERNAME}/{PROJECT_REPONAME}/tree/{args.branch}"
  github_url=f"https://github.com/{PROJECT_USERNAME}/{PROJECT_REPONAME}/commit/"
  repo = Repo(search_parent_directories=True)
  commit_msg = repo.head.commit.summary
  commit_author = repo.head.commit.author.name
  commit_sha = repo.head.object.hexsha
  commit_sha_short = commit_sha[:7]

  title = f"Pipelne {workflow.pipeline_number} : job {args.job_num} Failed :fire:"
  body = f"""
[CircleCI Job]({circleci_url}).
Branch: [{args.branch}]({branch_url})
Workflow: {workflow.name}
Commit: [{commit_sha_short}]({github_url}) {commit_msg}
Author: {commit_author}
"""

  return (title, body)

# Returns a url to the newly created issue
def failure_github_issue(args: FailureArgs) -> str:
  if not re.match(args.branch_pattern, args.branch):
    print(f"Branch {args.branch} does not match pattern {args.branch_pattern}, skipping notification")
    exit(0)

  workflow = fetch_workflow(args.workflow_id)
  (title, body) = get_msg(args, workflow)

  if args.dry_run:
    print(f"Creating an issue with title: {title} and body: {body}")
    return

  transport = RequestsHTTPTransport(url="https://api.github.com/graphql",
                                    headers={'Authorization': 'token ' + GH_TOKEN})
  client = Client(transport=transport, fetch_schema_from_transport=True)

  repo_id = get_repo_id(client, GH_ORGANIZATION, GH_REPO)
  project_id = get_project_id(client, GH_ORGANIZATION, GH_FAILURES_PROJECT)
  fields = get_project_fields(client, project_id)
  cluster_field_id = fields.get_field_id("Cluster")
  job_field_id = fields.get_field_id("Job")

  issue_id = create_issue(client, repo_id, title, body)
  issue_project_item_id = add_issue_to_project(client, project_id, issue_id)
  set_field_value(client, project_id, issue_project_item_id, cluster_field_id, args.cluster)
  set_field_value(client, project_id, issue_project_item_id, job_field_id, f"{workflow.name}:{args.job_name}")

  issue_number = get_issue_number(client, issue_id)
  issue_url = f"https://github.com/{GH_ORGANIZATION}/{GH_REPO}/issues/{issue_number}"

  return issue_url
