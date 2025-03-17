# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import flask
from flask import jsonify
import googleapiclient.discovery
import functions_framework

@functions_framework.http
def main(request: flask.Request) -> flask.typing.ResponseReturnValue:
    if not request.args or "before" not in request.args:
        return "Query parameter 'before' is required", 400
    sql = googleapiclient.discovery.build("sqladmin", "v1beta4")
    before = request.args["before"]
    latest = None
    pageToken = None
    if "instance" in request.args:
        instance = request.args["instance"]
        filter = f"instance = {instance} AND type = ON_DEMAND"
    else:
        filter = f"type = ON_DEMAND"
    while True:
        response = sql.backups().listBackups(parent = "projects/da-cn-ci-2", filter=filter, pageToken = pageToken).execute()
        if not "backups" in response:
            return "No backups found", 404
        backups = response["backups"]
        backupsBefore = [b for b in backups if b["backupInterval"]["endTime"] < before and b["state"] == "SUCCESSFUL" and b["instance"].startswith("sv-1-sequencer")]
        if len(backupsBefore) > 0:
            latestInBatch = max(backupsBefore, key = lambda b: b["backupInterval"]["endTime"])
            print(f'latestInBatch: {latestInBatch["backupInterval"]["endTime"]}')
            if not latest or latestInBatch["backupInterval"]["endTime"] > latest["backupInterval"]["endTime"]:
                latest = latestInBatch
        else:
            print(f"Nothing before {before} in batch")
        if not "nextPageToken" in response:
            if latest:
                return jsonify(latest)
            return "No backups found", 404
        pageToken = response["nextPageToken"]

