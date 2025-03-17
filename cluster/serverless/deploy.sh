#! /usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# consider moving this to pulumi. Not sure we gain much by doing that though. Or maybe a Makefile?

set -euo pipefail

SERVICE_ACCOUNT="offline-backups@da-cn-shared.iam.gserviceaccount.com"

# In this script we manage the permissions that the service account needs on the project where
# the CloudSQL instance with the backups is located. The account also has permissions in da-cn-shared,
# where it is installed and running, but those were added once manually since they are not
# in the prod project.

# Grant the SA permission to read from CloudSQL
gcloud projects add-iam-policy-binding \
  da-cn-mainnet \
  --member "serviceAccount:$SERVICE_ACCOUNT" \
  --role roles/cloudsql.viewer \
  --condition=None

# Grant the SA permission to deploy resources to clusters in the project
gcloud projects add-iam-policy-binding \
  da-cn-mainnet \
  --member "serviceAccount:$SERVICE_ACCOUNT" \
  --role roles/container.serviceAgent \
  --condition=None

gcloud beta run deploy \
  find-backup-before \
  --source "$REPO_ROOT/cluster/serverless/find-backup-before" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --no-allow-unauthenticated

gcloud beta run deploy \
  find-cloudsql-backups \
  --source "$REPO_ROOT/cluster/serverless/find-cloudsql-backups" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --no-allow-unauthenticated

gcloud beta run deploy \
  sa-backup-permissions \
  --source "$REPO_ROOT/cluster/serverless/sa-backup-permissions" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --no-allow-unauthenticated \
  --max-instances 1 \
  --concurrency 1
# (note that max_instances and concurrency are critical, as this function is not safe for parallel execution)

gcloud workflows deploy copy-cn-backup-to-bucket \
  --location=us-central1 \
  --source "$REPO_ROOT/cluster/serverless/workflows/copy-cn-backup-to-bucket.yaml" \
  --project da-cn-shared \
  --service-account "$SERVICE_ACCOUNT"
