#!/usr/bin/env python3

import json
import sys

j = json.load(sys.stdin)

result = {}
resources = j["deployment"]["resources"]
releases = [r for r in resources if r["type"] == "kubernetes:helm.sh/v3:Release"]
for release in releases:
    outputs = release["outputs"]
    status = outputs["status"]
    namespace = status["namespace"]
    chart = status["chart"]
    appVersion = outputs["values"]["cluster"]["imageTag"]
    value = result.get(namespace, {})
    value[chart] = appVersion
    result[namespace] = value

print(json.dumps(result, sort_keys=True, indent=2))
