#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SUBCOMMAND="$1"
shift

if [[ $SUBCOMMAND != create-pub-rep-slot && $SUBCOMMAND != delete-pub-rep-slot ]]; then
  echo "Error: Invalid subcommand '$SUBCOMMAND'. Must be 'create-pub-rep-slot' or 'delete-pub-rep-slot'." >&2
  exit 1
fi

REQUIRED_ARGS=(
  "private-network-project"
  "compute-region"
  "service-account-email"
  "tables-to-replicate-length"
  "db-name"
  "schema-name"
  "tables-to-replicate-list"
  "tables-to-replicate-joined"
  "postgres-user-name"
  "publication-name"
  "replication-slot-name"
  "replicator-user-name"
  "postgres-instance-name"
  "scan-app-database-name"
)
PRIVATE_NETWORK_PROJECT=""
COMPUTE_REGION=""
SERVICE_ACCOUNT_EMAIL=""
TABLES_TO_REPLICATE_LENGTH=""
DB_NAME=""
SCHEMA_NAME=""
TABLES_TO_REPLICATE_LIST=""
TABLES_TO_REPLICATE_JOINED=""
POSTGRES_USER_NAME=""
PUBLICATION_NAME=""
REPLICATION_SLOT_NAME=""
REPLICATOR_USER_NAME=""
POSTGRES_INSTANCE_NAME=""
SCAN_APP_DATABASE_NAME=""

# Track which arguments have been provided
declare -A PROVIDED_ARGS

while [ "$#" -gt 0 ]; do
  # Verify argument follows --name=value format
  if [[ ! "$1" =~ ^--[a-zA-Z0-9_-]+=.* ]]; then
    echo "Error: Invalid argument format: $1" >&2
    echo "Expected format: --name=value" >&2
    exit 1
  fi

  # Extract parameter name and value
  param_name="${1#--}"
  param_name="${param_name%%=*}"
  param_value="${1#*=}"

  # Check for duplicate parameters
  if [[ -n "${PROVIDED_ARGS[$param_name]:-}" ]]; then
    echo "Error: --$param_name specified more than once" >&2
    exit 1
  fi

  # Set appropriate variable based on parameter name
  case "$param_name" in
    private-network-project)
      PRIVATE_NETWORK_PROJECT="$param_value"
      ;;
    compute-region)
      COMPUTE_REGION="$param_value"
      ;;
    service-account-email)
      SERVICE_ACCOUNT_EMAIL="$param_value"
      ;;
    tables-to-replicate-length)
      TABLES_TO_REPLICATE_LENGTH="$param_value"
      ;;
    db-name)
      DB_NAME="$param_value"
      ;;
    schema-name)
      SCHEMA_NAME="$param_value"
      ;;
    tables-to-replicate-list)
      TABLES_TO_REPLICATE_LIST="$param_value"
      ;;
    tables-to-replicate-joined)
      TABLES_TO_REPLICATE_JOINED="$param_value"
      ;;
    postgres-user-name)
      POSTGRES_USER_NAME="$param_value"
      ;;
    publication-name)
      PUBLICATION_NAME="$param_value"
      ;;
    replication-slot-name)
      REPLICATION_SLOT_NAME="$param_value"
      ;;
    replicator-user-name)
      REPLICATOR_USER_NAME="$param_value"
      ;;
    postgres-instance-name)
      POSTGRES_INSTANCE_NAME="$param_value"
      ;;
    scan-app-database-name)
      SCAN_APP_DATABASE_NAME="$param_value"
      ;;
    *)
      echo "Unknown parameter: --$param_name" >&2
      exit 1
      ;;
  esac

  # Mark parameter as provided
  PROVIDED_ARGS["$param_name"]=1

  shift
done

# Check that all required arguments were provided
for arg in "${REQUIRED_ARGS[@]}"; do
  if [[ -z "${PROVIDED_ARGS[$arg]:-}" ]]; then
    echo "Error: Required argument --$arg not provided" >&2
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
echo 'Current gcloud login:'
gcloud auth list --format=config

TMP_BUCKET="da-cn-tmpsql-$(date +%s)-$RANDOM-b"
TMP_SQL_FILE="$(mktemp tmp_pub_rep_slots_XXXXXXXXXX.sql --tmpdir)"
GCS_URI="gs://$TMP_BUCKET/$(basename "$TMP_SQL_FILE")"

# don't bother setting up cleanup until we are logged in and start creating
# things to clean up
cleanup() {
  echo 'Cleaning up temporary GCS object and bucket'
  gsutil rm "$GCS_URI" || true
  gsutil rb "gs://$TMP_BUCKET" || true
  rm "$TMP_SQL_FILE" || true
}
trap cleanup EXIT

# create temporary bucket
echo "Creating temporary bucket $TMP_BUCKET"
gsutil mb --pap enforced -p "$PRIVATE_NETWORK_PROJECT" \
    -l "$COMPUTE_REGION" "gs://$TMP_BUCKET"

# grant DB service account access to the bucket
echo "Granting CloudSQL DB access to $TMP_BUCKET"
gsutil iam ch "serviceAccount:$SERVICE_ACCOUNT_EMAIL:roles/storage.objectAdmin" \
    "gs://$TMP_BUCKET"

case "$SUBCOMMAND" in
  create-pub-rep-slot)
    cat > "$TMP_SQL_FILE" <<EOT
      DO \$\$
      DECLARE
        migration_complete BOOLEAN := FALSE;
        max_attempts INT := 30; -- Try for 5 minutes (30 attempts * 10 seconds)
        attempt INT := 0;
      BEGIN
        WHILE NOT migration_complete AND attempt < max_attempts LOOP
          -- Check if all tables exist AND have the record_time column
          -- this is added by V037__denormalize_update_history.sql
          SELECT COUNT(*) = $TABLES_TO_REPLICATE_LENGTH INTO migration_complete
            FROM information_schema.columns
            WHERE table_catalog = '$DB_NAME'
              AND table_schema = '$SCHEMA_NAME'
              AND table_name IN ($TABLES_TO_REPLICATE_LIST)
              AND column_name = 'record_time';

          IF NOT migration_complete THEN
            RAISE NOTICE 'Waiting for update_history tables (attempt %/%), sleeping 10s...', attempt + 1, max_attempts;
            PERFORM pg_sleep(10);
            attempt := attempt + 1;
          END IF;
        END LOOP;

        IF NOT migration_complete THEN
          RAISE EXCEPTION 'Timed out waiting for update_history tables to be created';
        END IF;
      END \$\$;
      SET search_path TO $SCHEMA_NAME;
      -- from https://cloud.google.com/datastream/docs/configure-cloudsql-psql
      ALTER USER $POSTGRES_USER_NAME WITH REPLICATION; -- needed to create the replication slot
      DO \$\$
      BEGIN
        -- TODO (#453) drop slot, pub if table list doesn't match
        IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = '$PUBLICATION_NAME') THEN
          CREATE PUBLICATION $PUBLICATION_NAME
            FOR TABLE $TABLES_TO_REPLICATE_JOINED;
        END IF;
      END \$\$;
      COMMIT; -- otherwise fails with "cannot create logical replication slot
              -- in transaction that has performed writes"
      DO \$\$
      BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '$REPLICATION_SLOT_NAME') THEN
          PERFORM PG_CREATE_LOGICAL_REPLICATION_SLOT
            ('$REPLICATION_SLOT_NAME', 'pgoutput');
        END IF;
      END \$\$;
      COMMIT;
      ALTER USER $REPLICATOR_USER_NAME WITH REPLICATION;
      GRANT SELECT ON ALL TABLES
        IN SCHEMA $SCHEMA_NAME TO $REPLICATOR_USER_NAME;
      GRANT USAGE ON SCHEMA $SCHEMA_NAME TO $REPLICATOR_USER_NAME;
      ALTER DEFAULT PRIVILEGES IN SCHEMA $SCHEMA_NAME
        GRANT SELECT ON TABLES TO $REPLICATOR_USER_NAME;
      COMMIT;
EOT
    ;;
  delete-pub-rep-slot)
    cat > "$TMP_SQL_FILE" <<EOT
      SET search_path TO $SCHEMA_NAME;
      DO \$\$
      BEGIN
        IF EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '$REPLICATION_SLOT_NAME') THEN
          PERFORM PG_DROP_REPLICATION_SLOT('$REPLICATION_SLOT_NAME');
        END IF;
      END \$\$;
      DO \$\$
      BEGIN
        IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = '$PUBLICATION_NAME') THEN
          DROP PUBLICATION $PUBLICATION_NAME;
        END IF;
      END \$\$;
EOT
    ;;
esac

echo 'Uploading SQL to temporary bucket'
gsutil cp "$TMP_SQL_FILE" "$GCS_URI"

echo 'Importing into CloudSQL'
gcloud sql import sql "$POSTGRES_INSTANCE_NAME" "$GCS_URI" \
  --database="$SCAN_APP_DATABASE_NAME" \
  --user="$POSTGRES_USER_NAME" \
  --quiet
