#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Run the SQL read from stdin against a private-IP-only CloudSQL instance, via
# `gcloud sql import sql`. Trimmed copy of the import half of
# cluster/pulumi/canton-network/bigquery-cloudsql.sh.

set -euo pipefail

PROJECT=""
COMPUTE_REGION=""
SERVICE_ACCOUNT_EMAIL=""
POSTGRES_INSTANCE_NAME=""
DATABASE=""
POSTGRES_USER=""
BUCKET_LABELS=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project=*) PROJECT="${1#*=}" ;;
    --compute-region=*) COMPUTE_REGION="${1#*=}" ;;
    --service-account-email=*) SERVICE_ACCOUNT_EMAIL="${1#*=}" ;;
    --postgres-instance-name=*) POSTGRES_INSTANCE_NAME="${1#*=}" ;;
    --database=*) DATABASE="${1#*=}" ;;
    --user=*) POSTGRES_USER="${1#*=}" ;;
    --bucket-labels=*) BUCKET_LABELS="${1#*=}" ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
  shift
done

for var in PROJECT COMPUTE_REGION SERVICE_ACCOUNT_EMAIL POSTGRES_INSTANCE_NAME DATABASE POSTGRES_USER; do
  if [[ -z "${!var}" ]]; then
    echo "Error: $var not provided" >&2
    exit 1
  fi
done

if [[ -s "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
  echo "Using $GOOGLE_APPLICATION_CREDENTIALS for authentication"
  gcloud auth activate-service-account --key-file="$GOOGLE_APPLICATION_CREDENTIALS"
elif [[ -n "${GOOGLE_CREDENTIALS:-}" ]]; then
  echo "Using GOOGLE_CREDENTIALS for authentication"
  echo "$GOOGLE_CREDENTIALS" | gcloud auth activate-service-account --key-file=-
else
  echo 'No GCP credentials found, using default'
fi

TMP_BUCKET="da-cn-tmpsql-$(date +%s)-$RANDOM-b"
TMP_SQL_FILE="$(mktemp tmp_import_sql_XXXXXXXXXX.sql --tmpdir)"
GCS_URI="gs://$TMP_BUCKET/$(basename "$TMP_SQL_FILE")"

cleanup() {
  echo 'Cleaning up temporary GCS object and bucket'
  gcloud storage rm "$GCS_URI" || true
  gcloud storage buckets delete "gs://$TMP_BUCKET" || true
  rm "$TMP_SQL_FILE" || true
}
trap cleanup EXIT

cat > "$TMP_SQL_FILE"

echo "Creating temporary bucket $TMP_BUCKET"
gcloud storage buckets create --pap --project "$PROJECT" \
    -l "$COMPUTE_REGION" "gs://$TMP_BUCKET"

if [[ -n "$BUCKET_LABELS" ]]; then
  # `buckets create` takes no labels; label it so a bucket leaked by a failed
  # cleanup can be traced back to its stack
  gcloud storage buckets update "gs://$TMP_BUCKET" --update-labels="$BUCKET_LABELS"
fi

echo "Granting CloudSQL DB access to $TMP_BUCKET"
gcloud storage buckets add-iam-policy-binding "gs://$TMP_BUCKET" \
  --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
  --role="roles/storage.objectAdmin"

echo 'Uploading SQL to temporary bucket'
gcloud storage cp "$TMP_SQL_FILE" "$GCS_URI"

echo 'Importing into CloudSQL'
gcloud sql import sql "$POSTGRES_INSTANCE_NAME" "$GCS_URI" \
  --database="$DATABASE" \
  --user="$POSTGRES_USER" \
  --quiet
