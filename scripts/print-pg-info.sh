#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ "$#" -ne 2 ]; then
  echo "This script prints information required to connect to the Postgres database of a deployed application."

  echo "Usage: $(basename "$0") <secret-name> <db-app-name>"
  echo "Example: $(basename "$0") cn-apps-pg-secrets pge-cn-apps-pg-scan"
  exit 1
fi

dbSecretName="$1"
dbAppName="$2"

namespaces=$(kubectl get pods -A | grep "$dbAppName" | awk '{print $1}' | sort)

output="NAMESPACE DB_POD DB_IP DB_PASSWORD"

while read -r namespace; do
  echo "Getting information for namespace $namespace"

  dbPassword=$(kubectl get secret -n "$namespace" "$dbSecretName" -o jsonpath='{.data.*}' | base64 -d)
  dbPod=$(kubectl describe pod -n "$namespace" "$dbAppName" | grep "^Name:" | awk '{print $2}')
  dbIp=$(kubectl describe pod -n "$namespace" "$dbAppName" | grep "^IP:" | awk '{print $2}')

  output="$output\n${namespace} ${dbPod} ${dbIp} ${dbPassword}"
done <<< "${namespaces}"

echo -e "${output}" | column -t -s ' '
