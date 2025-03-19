# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import flask
import json
import googleapiclient.discovery
import functions_framework

@functions_framework.http
def main(request: flask.Request) -> flask.typing.ResponseReturnValue:
  if not request.args or "bucket" not in request.args:
    return "Query parameter 'bucket' is required", 400
  if not "serviceAccount" in request.args:
    return "Query parameter 'serviceAccount' is required", 400
  if not "operation" in request.args:
    return "Query parameter 'operation' is required", 400
  if request.args["operation"] not in ["add", "remove"]:
    return "Query parameter 'operation' must be 'add' or 'remove'", 400
  if request.args["operation"] == "add":
    bucket = request.args["bucket"]
    serviceAccount = request.args["serviceAccount"]
    storage = googleapiclient.discovery.build("storage", "v1")
    policy = storage.buckets().getIamPolicy(bucket=bucket).execute()
    if not "bindings" in policy:
      bindings = []
    else:
      bindings = policy["bindings"]
    bindings.append({"role": "roles/storage.objectAdmin", "members": [f"serviceAccount:{serviceAccount}"]})
    storage.buckets().setIamPolicy(bucket=bucket, body={"bindings": bindings}).execute()
    policy["bindings"] = bindings
    print(f"Policy after adding: {json.dumps(policy)}")
  else:
    if not request.args or "bucket" not in request.args:
      return "Query parameter 'bucket' is required", 400
    if not "serviceAccount" in request.args:
      return "Query parameter 'serviceAccount' is required", 400
    bucket = request.args["bucket"]
    serviceAccount = request.args["serviceAccount"]
    storage = googleapiclient.discovery.build("storage", "v1")
    policy = storage.buckets().getIamPolicy(bucket=bucket).execute()
    print(f"Policy before removing {serviceAccount}: {json.dumps(policy)}")
    if not "bindings" in policy:
      return "No bindings found", 404
    bindings = policy["bindings"]
    newBindings = [b for b in bindings if not b["role"] == "roles/storage.objectAdmin"]
    objectAdminMembers = [m for b in policy["bindings"] if b["role"] == "roles/storage.objectAdmin" for m in b["members"]]
    print(f"Members of objectAdmin role: {objectAdminMembers}")
    if not f"serviceAccount:{serviceAccount}" in objectAdminMembers:
      return "Service account not found in objectAdmin role", 404
    newObjectAdminMembers = [m for m in objectAdminMembers if not m == f"serviceAccount:{serviceAccount}"]
    if len(newObjectAdminMembers) > 0:
      newBindings.append({"role": "roles/storage.objectAdmin", "members": newObjectAdminMembers})
    print(f"New members of objectAdmin role: {newObjectAdminMembers}")
    storage.buckets().setIamPolicy(bucket=bucket, body={"bindings": newBindings}).execute()
    policy["bindings"] = newBindings
    print(f"Full policy after removing {serviceAccount}: {json.dumps(policy)}")
  return "done"
