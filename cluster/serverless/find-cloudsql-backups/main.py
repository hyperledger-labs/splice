# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import flask
from flask import jsonify
import googleapiclient.discovery
import functions_framework

@functions_framework.http
def main(request: flask.Request) -> flask.typing.ResponseReturnValue:
    if not request.args or "cluster" not in request.args:
        return "Query parameter 'cluster' is required", 400
    if not "namespace" in request.args:
        return "Query parameter 'namespace' is required", 400
    if not "backup_run_id" in request.args:
        return "Query parameter 'backup_run_id' is required", 400
    if not "project" in request.args:
        return "Query parameter 'project' is required", 400
    cluster = request.args["cluster"]
    namespace = request.args["namespace"]
    backup_run_id = request.args["backup_run_id"]
    project = request.args["project"]
    sql = googleapiclient.discovery.build("sqladmin", "v1beta4")
    allInstances = []
    pageToken = None
    while True:
      instances = sql.instances().list(project=project, pageToken = pageToken).execute()
      if not "items" in instances:
          break
      allInstances.extend(instances["items"])
      if not "nextPageToken" in instances:
          break
    instances = [i for i in allInstances if "userLabels" in i["settings"] and "cluster" in i["settings"]["userLabels"] and i["settings"]["userLabels"]["cluster"] == cluster and i["name"].startswith(namespace)]

    found = []
    for instance in instances:
        pageToken = None
        while True:
            backups = sql.backups().listBackups(parent = f"projects/{project}", filter=f"instance = {instance["name"]} AND type = ON_DEMAND", pageToken = pageToken).execute()
            if not "backups" in backups:
                break
            filtered = [b for b in backups["backups"] if "description" in b and b["description"] == backup_run_id]
            if len(filtered) > 0:
                found.append({"instance": instance["name"], "backup": filtered[0]["name"]})
                break
            if not "nextPageToken" in backups:
                break
            pageToken = backups["nextPageToken"]
    return jsonify(found)
