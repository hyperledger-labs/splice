#! /usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# consider moving this to pulumi. Not sure we gain much by doing that though. Or maybe a Makefile?

set -euo pipefail

SERVICE_ACCOUNT="offline-backups@da-cn-shared.iam.gserviceaccount.com"
PROJECT="da-cn-shared"

gcloud beta run deploy \
  find-backup-before \
  --source "$SPLICE_ROOT/cluster/serverless/find-backup-before" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --project "$PROJECT" \
  --no-allow-unauthenticated

gcloud beta run deploy \
  find-cloudsql-backups \
  --source "$SPLICE_ROOT/cluster/serverless/find-cloudsql-backups" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --project "$PROJECT" \
  --no-allow-unauthenticated

gcloud beta run deploy \
  sa-bucket-permissions \
  --source "$SPLICE_ROOT/cluster/serverless/sa-bucket-permissions" \
  --function main \
  --base-image python312 \
  --region us-central1 \
  --project "$PROJECT" \
  --no-allow-unauthenticated \
  --max-instances 1 \
  --concurrency 1
# (note that max_instances and concurrency are critical, as this function is not safe for parallel execution)

gcloud workflows deploy copy-cn-backup-to-bucket \
  --location=us-central1 \
  --source "$SPLICE_ROOT/cluster/serverless/workflows/copy-cn-backup-to-bucket.yaml" \
  --project "$PROJECT" \
  --service-account "$SERVICE_ACCOUNT" \
  --call-log-level "log-all-calls"
