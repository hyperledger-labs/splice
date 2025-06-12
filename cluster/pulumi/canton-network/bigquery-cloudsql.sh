#!/usr/bin/env bash

set -e

        TMP_BUCKET="da-cn-tmpsql-$(date +%s)-$RANDOM-b"
        TMP_SQL_FILE="$(mktemp tmp_pub_rep_slots_XXXXXXXXXX.sql --tmpdir)"
        GCS_URI="gs://$TMP_BUCKET/$(basename "$TMP_SQL_FILE")"

        # create temporary bucket
        gsutil mb --pap enforced -p "${privateNetwork.project}" \
            -l "${cloudsdkComputeRegion()}" "gs://$TMP_BUCKET"

        # grant DB service account access to the bucket
        gsutil iam ch "serviceAccount:${postgres.databaseInstance.serviceAccountEmailAddress}:roles/storage.objectAdmin" \
            "gs://$TMP_BUCKET"

        cat > "$TMP_SQL_FILE" <<'EOT'
          DO $$
          DECLARE
            migration_complete BOOLEAN := FALSE;
            max_attempts INT := 30; -- Try for 5 minutes (30 attempts * 10 seconds)
            attempt INT := 0;
          BEGIN
            WHILE NOT migration_complete AND attempt < max_attempts LOOP
              -- Check if all tables exist AND have the record_time column
              -- this is added by V037__denormalize_update_history.sql
              SELECT COUNT(*) = ${tablesToReplicate.length} INTO migration_complete
                FROM information_schema.columns
                WHERE table_catalog = '${dbName}'
                  AND table_schema = '${schemaName}'
                  AND table_name IN (${tablesToReplicate.map(n => `'${n}'`).join(', ')})
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
          END $$;
          SET search_path TO ${schemaName};
          ALTER USER ${postgres.user.name} WITH REPLICATION; -- needed to create the replication slot
          DO $$
          BEGIN
            -- TODO (#19811) drop slot, pub if table list doesn't match
            IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = '${publicationName}') THEN
              CREATE PUBLICATION ${publicationName}
                FOR TABLE ${tablesToReplicate.join(', ')};
            END IF;
          END $$;
          COMMIT; -- otherwise fails with "cannot create logical replication slot
                  -- in transaction that has performed writes"
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '${replicationSlotName}') THEN
              PERFORM PG_CREATE_LOGICAL_REPLICATION_SLOT
                ('${replicationSlotName}', 'pgoutput');
            END IF;
          END $$;
          COMMIT;
          ALTER USER ${replicatorUserName} WITH REPLICATION;
          GRANT SELECT ON ALL TABLES
            IN SCHEMA ${schemaName} TO ${replicatorUserName};
          GRANT USAGE ON SCHEMA ${schemaName} TO ${replicatorUserName};
          ALTER DEFAULT PRIVILEGES IN SCHEMA ${schemaName}
            GRANT SELECT ON TABLES TO ${replicatorUserName};
          COMMIT;
EOT

        # upload SQL to temporary bucket
        gsutil cp "$TMP_SQL_FILE" "$GCS_URI"

        # then import into Cloud SQL
        gcloud sql import sql ${postgres.databaseInstance.name} "$GCS_URI" \
          --database="${scanAppDatabaseName(postgres)}" \
          --user="${postgres.user.name}" \
          --quiet

        # cleanup: remove the file from GCS, delete the bucket, remove the local file
        gsutil rm "$GCS_URI"
        gsutil rb "gs://$TMP_BUCKET"
        rm "$TMP_SQL_FILE"

}
