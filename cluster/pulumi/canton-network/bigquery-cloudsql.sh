#!/usr/bin/env bash

# Example invocation from Pulumi:
# ./bigquery-cloudsql.sh \
#   --private-network-project="${privateNetwork.project}" \
#   --compute-region="${cloudsdkComputeRegion()}" \
#   --service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}" \
#   --tables-to-replicate-length="${tablesToReplicate.length}" \
#   --db-name="${dbName}" \
#   --schema-name="${schemaName}" \
#   --tables-to-replicate-list="${tablesToReplicate.map(n => `'${n}'`).join(', ')}" \
#   --tables-to-replicate-joined="${tablesToReplicate.join(', ')}" \
#   --postgres-user-name="${postgres.user.name}" \
#   --publication-name="${publicationName}" \
#   --replication-slot-name="${replicationSlotName}" \
#   --replicator-user-name="${replicatorUserName}" \
#   --postgres-instance-name="${postgres.databaseInstance.name}" \
#   --scan-app-database-name="${scanAppDatabaseName(postgres)}"

# Parse named arguments
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
  case "$1" in
    --private-network-project=*)
      if [ -n "${PROVIDED_ARGS[private-network-project]}" ]; then
        echo "Error: --private-network-project specified more than once" >&2
        exit 1
      fi
      PRIVATE_NETWORK_PROJECT="${1#*=}"
      PROVIDED_ARGS[private-network-project]=1
      ;;
    --compute-region=*)
      if [ -n "${PROVIDED_ARGS[compute-region]}" ]; then
        echo "Error: --compute-region specified more than once" >&2
        exit 1
      fi
      COMPUTE_REGION="${1#*=}"
      PROVIDED_ARGS[compute-region]=1
      ;;
    --service-account-email=*)
      if [ -n "${PROVIDED_ARGS[service-account-email]}" ]; then
        echo "Error: --service-account-email specified more than once" >&2
        exit 1
      fi
      SERVICE_ACCOUNT_EMAIL="${1#*=}"
      PROVIDED_ARGS[service-account-email]=1
      ;;
    --tables-to-replicate-length=*)
      if [ -n "${PROVIDED_ARGS[tables-to-replicate-length]}" ]; then
        echo "Error: --tables-to-replicate-length specified more than once" >&2
        exit 1
      fi
      TABLES_TO_REPLICATE_LENGTH="${1#*=}"
      PROVIDED_ARGS[tables-to-replicate-length]=1
      ;;
    --db-name=*)
      if [ -n "${PROVIDED_ARGS[db-name]}" ]; then
        echo "Error: --db-name specified more than once" >&2
        exit 1
      fi
      DB_NAME="${1#*=}"
      PROVIDED_ARGS[db-name]=1
      ;;
    --schema-name=*)
      if [ -n "${PROVIDED_ARGS[schema-name]}" ]; then
        echo "Error: --schema-name specified more than once" >&2
        exit 1
      fi
      SCHEMA_NAME="${1#*=}"
      PROVIDED_ARGS[schema-name]=1
      ;;
    --tables-to-replicate-list=*)
      if [ -n "${PROVIDED_ARGS[tables-to-replicate-list]}" ]; then
        echo "Error: --tables-to-replicate-list specified more than once" >&2
        exit 1
      fi
      TABLES_TO_REPLICATE_LIST="${1#*=}"
      PROVIDED_ARGS[tables-to-replicate-list]=1
      ;;
    --tables-to-replicate-joined=*)
      if [ -n "${PROVIDED_ARGS[tables-to-replicate-joined]}" ]; then
        echo "Error: --tables-to-replicate-joined specified more than once" >&2
        exit 1
      fi
      TABLES_TO_REPLICATE_JOINED="${1#*=}"
      PROVIDED_ARGS[tables-to-replicate-joined]=1
      ;;
    --postgres-user-name=*)
      if [ -n "${PROVIDED_ARGS[postgres-user-name]}" ]; then
        echo "Error: --postgres-user-name specified more than once" >&2
        exit 1
      fi
      POSTGRES_USER_NAME="${1#*=}"
      PROVIDED_ARGS[postgres-user-name]=1
      ;;
    --publication-name=*)
      if [ -n "${PROVIDED_ARGS[publication-name]}" ]; then
        echo "Error: --publication-name specified more than once" >&2
        exit 1
      fi
      PUBLICATION_NAME="${1#*=}"
      PROVIDED_ARGS[publication-name]=1
      ;;
    --replication-slot-name=*)
      if [ -n "${PROVIDED_ARGS[replication-slot-name]}" ]; then
        echo "Error: --replication-slot-name specified more than once" >&2
        exit 1
      fi
      REPLICATION_SLOT_NAME="${1#*=}"
      PROVIDED_ARGS[replication-slot-name]=1
      ;;
    --replicator-user-name=*)
      if [ -n "${PROVIDED_ARGS[replicator-user-name]}" ]; then
        echo "Error: --replicator-user-name specified more than once" >&2
        exit 1
      fi
      REPLICATOR_USER_NAME="${1#*=}"
      PROVIDED_ARGS[replicator-user-name]=1
      ;;
    --postgres-instance-name=*)
      if [ -n "${PROVIDED_ARGS[postgres-instance-name]}" ]; then
        echo "Error: --postgres-instance-name specified more than once" >&2
        exit 1
      fi
      POSTGRES_INSTANCE_NAME="${1#*=}"
      PROVIDED_ARGS[postgres-instance-name]=1
      ;;
    --scan-app-database-name=*)
      if [ -n "${PROVIDED_ARGS[scan-app-database-name]}" ]; then
        echo "Error: --scan-app-database-name specified more than once" >&2
        exit 1
      fi
      SCAN_APP_DATABASE_NAME="${1#*=}"
      PROVIDED_ARGS[scan-app-database-name]=1
      ;;
    *)
      echo "Unknown parameter: $1" >&2
      exit 1
      ;;
  esac
  shift
done

# Check that all required arguments were provided
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

for arg in "${REQUIRED_ARGS[@]}"; do
  if [ -z "${PROVIDED_ARGS[$arg]}" ]; then
    echo "Error: Required argument --$arg not provided" >&2
    exit 1
  fi
done

set -e

TMP_BUCKET="da-cn-tmpsql-$(date +%s)-$RANDOM-b"
TMP_SQL_FILE="$(mktemp tmp_pub_rep_slots_XXXXXXXXXX.sql --tmpdir)"
GCS_URI="gs://$TMP_BUCKET/$(basename "$TMP_SQL_FILE")"

# create temporary bucket
gsutil mb --pap enforced -p "$PRIVATE_NETWORK_PROJECT" \
    -l "$COMPUTE_REGION" "gs://$TMP_BUCKET"

# grant DB service account access to the bucket
gsutil iam ch "serviceAccount:$SERVICE_ACCOUNT_EMAIL:roles/storage.objectAdmin" \
    "gs://$TMP_BUCKET"

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
  ALTER USER $POSTGRES_USER_NAME WITH REPLICATION; -- needed to create the replication slot
  DO \$\$
  BEGIN
    -- TODO (#19811) drop slot, pub if table list doesn't match
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

# upload SQL to temporary bucket
gsutil cp "$TMP_SQL_FILE" "$GCS_URI"

# then import into Cloud SQL
gcloud sql import sql $POSTGRES_INSTANCE_NAME "$GCS_URI" \
  --database="$SCAN_APP_DATABASE_NAME" \
  --user="$POSTGRES_USER_NAME" \
  --quiet

# cleanup: remove the file from GCS, delete the bucket, remove the local file
gsutil rm "$GCS_URI"
gsutil rb "gs://$TMP_BUCKET"
rm "$TMP_SQL_FILE"
