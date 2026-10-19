# pg-partition-bq-test

This is a throwaway CloudSQL → Datastream → BigQuery pipeline whose source is a
range-partitioned Postgres table. It exists to validate the
[#6312](https://github.com/canton-network/splice/issues/6312) cutover of the scan
`update_history_*` tables to partitioned parents before the migration ships, in
particular the `publish_via_partition_root = true` publication setting from
[#6730](https://github.com/canton-network/splice/issues/6730).

The stack mirrors the **stag-prod** stream in `common-sv/src/bigQuery.ts`:
`appendOnly`, `backfillNone`, and HOUR ingestion-time partitioning. Because of
`backfillNone`, Datastream backfill is not part of this test. The publication and
replication slot are created by the real
`cluster/pulumi/canton-network/bigquery-cloudsql.sh`.

The stack is applied manually only. It is not part of any deploy flow or of `make update-expected`.

## What gets deployed

| Resource | Name |
|---|---|
| CloudSQL instance (private IP, `cloudsql.logical_decoding=on`) | `<cluster>-pgpart-db-<suffix>` |
| Database and schema | `partition_test` |
| Users | `cnadmin` (owns the tables), `bqdatastream` (Datastream) |
| Tables | `partitioned_events` (partitioned from the start), `cutover_events` (plain, swapped in section 2) |
| Publication and slot | `pgpart_stag_prod_pub` (`publish_via_partition_root = true`), `pgpart_stag_prod_r_slot` |
| NAT VM, Datastream private connection, firewall | `<cluster>-pgpart-*`; peering /29 is `GCP_MASTER_IPV4_CIDR` + 24 |
| BigQuery dataset | `pgpart_<cluster>` |
| Datastream stream | `<cluster>-pgpart-stag-prod` |

Datastream names the BigQuery tables `<schema>_<table>`, so the two tables
expected in the dataset are `partition_test_partitioned_events` and
`partition_test_cutover_events`.

## Deploy

You need the same GCP permissions as for deploying scan's BigQuery pipeline:
Cloud SQL Editor, permission to create GCS buckets, and Datastream and BigQuery
admin. The Datastream API must be enabled in the project; check with
`gcloud services list --enabled | grep datastream`.

```bash
cd cluster/deployment/scratchnetd
cncluster pulumi pg-partition-bq-test up
cncluster pulumi pg-partition-bq-test stack output --show-secrets
```

The first `up` creates
`cluster/stacks/scratchnets/pg-partition-bq-test/Pulumi.pg-partition-bq-test.scratchd.yaml`.
Commit it alongside this project.

Set these variables for the commands below, filled from the stack outputs:

```bash
PRIVATE_IP=...          # privateIp
PGPASSWORD=...          # cnadminPassword
BQ_DATASET=...          # bigQueryDataset, e.g. da-cn-scratchnet.pgpart_scratchd
STREAM=...              # datastreamStreamId
```

### psql

The instance has only a private IP. The scratchnet's GKE cluster is in the same
VPC, so run a client pod there. Every node pool carries a `NoSchedule` taint
(`cn_apps`, or `cloud.google.com/compute-class` on clusters with compute
classes; see `cluster/pulumi/cluster/src/nodePools.ts`), so the pod needs a
toleration, or it stays `Pending` until kubectl gives up with `timed out
waiting for the condition`:

```bash
kubectl run pgpart-psql --rm -it --restart=Never --image=postgres:18 \
  --labels=testing=pgpart-pr7737 \
  --pod-running-timeout=5m \
  --overrides='{"apiVersion": "v1", "spec": {"tolerations": [
    {"key": "cn_apps", "operator": "Exists", "effect": "NoSchedule"},
    {"key": "cloud.google.com/compute-class", "operator": "Exists", "effect": "NoSchedule"}]}}' \
  --env=PGPASSWORD="$PGPASSWORD" \
  -- psql -h "$PRIVATE_IP" -U cnadmin -d partition_test
```

If it still doesn't start, `kubectl describe pod pgpart-psql` from a second
terminal shows why.

As a fallback, `gcloud compute ssh <natVmName> --zone <natVmZone>`, then
`sudo apt-get install -y postgresql-client` and run the same `psql` command.
The VM uses OS Login, which has two common failure modes:

- `Permission denied (publickey)`: your account lacks an OS Login role.
  `apt-get` needs sudo, so that's `roles/compute.osAdminLogin` on the project
  or the VM. `gcloud compute ssh <natVmName> --zone <natVmZone> --troubleshoot`
  checks this, among other things.
- A prompt for the passphrase of `~/.ssh/google_compute_engine` means that key
  is passphrase-protected; with a passphrase-free key ssh never asks.
  `ssh-keygen -y -f ~/.ssh/google_compute_engine` asks too in that case. Enter
  its passphrase, or move the key pair aside and let `gcloud compute ssh`
  generate a new one (just press enter at `Enter passphrase (empty for no
  passphrase)`).

Start every psql session with:

```sql
SET search_path TO partition_test;
SET TIME ZONE 'UTC';
```

### BigQuery

```bash
bq query --use_legacy_sql=false --label=testing:pgpart-pr7737 "<query>"
```

The BigQuery samples below are written for that double-quoted argument: their
backticks are escaped, and the shell expands `$BQ_DATASET`.

Datastream writes with a delay of a few minutes, even with `dataFreshness: 0s`.
Re-run BigQuery checks until the numbers stop changing before you conclude anything.
To check the stream status and per-object errors:

```bash
gcloud datastream streams describe "$STREAM" --location=us-central1 --format='value(state,errors)'
gcloud datastream objects list --stream="$STREAM" --location=us-central1
```

## 0. Publication sanity

```sql
SELECT pubname, pubviaroot FROM pg_publication;
-- pgpart_stag_prod_pub, t

SELECT * FROM pg_publication_tables WHERE pubname = 'pgpart_stag_prod_pub';
-- partitioned_events and cutover_events only, with no partitions such as
-- partitioned_events_import
```

## 1. Partitions and CDC

Create daily partitions for yesterday, today and tomorrow, in the same shape the
#6312 migration uses (`record_time` in microseconds since the epoch):

```sql
DO $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(current_date - 1, current_date + 1, interval '1 day')::date LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF partitioned_events FOR VALUES FROM (%s) TO (%s)',
      'partitioned_events_p' || to_char(d, 'YYYYMMDD'),
      (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
      (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
  END LOOP;
END $$;
-- DO
```

Confirm the partitions exist:

```sql
\d+ partitioned_events
-- 'Partitions:' should show 4: the _import created in index.ts,
-- and 3 dated ones with different ranges

SELECT inhrelid::regclass AS partition, pg_get_expr(c.relpartbound, c.oid) AS bound
  FROM pg_inherits JOIN pg_class c ON c.oid = inhrelid
  WHERE inhparent = 'partitioned_events'::regclass ORDER BY 1;
-- lower-level version of the above
```

Insert rows into every partition, including the import partition, and count them per partition:

```sql
INSERT INTO partitioned_events (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'phase1 ' || t
  FROM generate_series(current_date - 1 + interval '1 hour',
                       current_date + 1 + interval '23 hours', interval '6 hours') AS t;
-- INSERT 0 12
INSERT INTO partitioned_events (record_time, payload) VALUES (-62135596800000000, 'phase1 import');
-- INSERT 0 1

SELECT tableoid::regclass, count(*) FROM partitioned_events GROUP BY 1 ORDER BY 1;
-- 1 in import, 4 in each other partition

SELECT count(*), min(row_id), max(row_id) FROM partitioned_events;
-- 13, 1, 13
```

Check BigQuery (assuming shell command from above, double-quoted argument):

```sql
SELECT table_name FROM \`$BQ_DATASET\`.INFORMATION_SCHEMA.TABLES ORDER BY 1;
-- expect only partition_test_partitioned_events

SELECT count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_partitioned_events\`;
-- expect 13, 1, 13 (matching above pg version)

SELECT row_id, count(*) FROM \`$BQ_DATASET.partition_test_partitioned_events\`
  GROUP BY row_id HAVING count(*) > 1;
-- expect no rows, empty output (meaning no duplicates)

SELECT datastream_metadata.change_type, count(*)
  FROM \`$BQ_DATASET.partition_test_partitioned_events\` GROUP BY 1;
-- expect INSERT, 13 (no other change types)
```

- **Expected:** the dataset has exactly one table per parent and nothing named
  after a partition, such as `…_partitioned_events_p20261012` or `…_import`.
- **Failure signal:** the stream lists only the parents in `includeObjects`, so a
  publication without `pubviaroot` would most likely **silently drop** the rows
  rather than create per-partition tables. That is why this step compares counts
  and not only table names.

Then add a partition for the day after tomorrow, after the stream is already
running, and insert a row into it:

```sql
DO $$
DECLARE d date := current_date + 2;
BEGIN
  EXECUTE format(
    'CREATE TABLE %I PARTITION OF partitioned_events FOR VALUES FROM (%s) TO (%s)',
    'partitioned_events_p' || to_char(d, 'YYYYMMDD'),
    (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
    (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
END $$;
-- DO

INSERT INTO partitioned_events (record_time, payload)
  VALUES ((extract(epoch FROM current_date + 2 + interval '12 hours') * 1000000)::bigint,
          'phase1 new partition');
-- INSERT 0 1

SELECT tableoid::regclass, row_id, payload FROM partitioned_events
  WHERE payload = 'phase1 new partition';
-- partitioned_events_p<day after tomorrow>, 14, phase1 new partition
```

Check that the row arrives in the same BigQuery table:

```sql
SELECT table_name FROM \`$BQ_DATASET\`.INFORMATION_SCHEMA.TABLES ORDER BY 1;
-- expect still only partition_test_partitioned_events

SELECT row_id, payload FROM \`$BQ_DATASET.partition_test_partitioned_events\`
  WHERE payload = 'phase1 new partition';
-- expect 14, phase1 new partition

SELECT count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_partitioned_events\`;
-- expect 14, 1, 14
```

## 2. Cutover simulation (#6312 rename swap)

Seed the plain table and confirm the rows reach BigQuery:

```sql
INSERT INTO cutover_events (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'pre-cutover ' || t
  FROM generate_series(now() - interval '2 hours', now(), interval '30 minutes') AS t;
-- INSERT 0 5

SELECT count(*), max(row_id) FROM cutover_events;
-- 5, 5
```

```sql
SELECT count(*), max(row_id) FROM \`$BQ_DATASET.partition_test_cutover_events\`;
-- expect 5, 5 (matching above pg version)
```

### 2a. Rename alone: what the publication does by default

Publications track tables by OID, so a renamed table stays published under its new name:

```sql
BEGIN;
ALTER TABLE cutover_events RENAME TO cutover_events_legacy;
SELECT * FROM pg_publication_tables WHERE pubname = 'pgpart_stag_prod_pub';
-- partitioned_events and cutover_events_legacy
ROLLBACK;
```

That is why the migration in 2b needs a publication step: after the cutover the
publication must **not** contain the `*_legacy` tables.

### 2b. Pseudo-Flyway migration

This block is written to be copied into the #6312 migration for each of the five
tables. The final `DO` block alters only the publications that contain the
renamed table. It is a no-op on deployments without Datastream, where there is
no publication.

```sql
BEGIN;

ALTER TABLE cutover_events RENAME TO cutover_events_legacy;
ALTER INDEX cutover_events_pkey RENAME TO cutover_events_legacy_pkey;

CREATE TABLE cutover_events (
  row_id bigint NOT NULL,
  record_time bigint NOT NULL,
  payload text,
  PRIMARY KEY (record_time, row_id)
) PARTITION BY RANGE (record_time);

-- move the sequence to the new parent; the legacy table is immutable from here on
ALTER TABLE cutover_events_legacy ALTER COLUMN row_id DROP DEFAULT;
ALTER SEQUENCE cutover_events_row_id_seq OWNED BY cutover_events.row_id;
ALTER TABLE cutover_events ALTER COLUMN row_id SET DEFAULT nextval('cutover_events_row_id_seq');

CREATE TABLE cutover_events_import
  PARTITION OF cutover_events FOR VALUES FROM (MINVALUE) TO (-62135596799999999);
DO $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(current_date - 1, current_date + 1, interval '1 day')::date LOOP
    EXECUTE format(
      'CREATE TABLE %I PARTITION OF cutover_events FOR VALUES FROM (%s) TO (%s)',
      'cutover_events_p' || to_char(d, 'YYYYMMDD'),
      (extract(epoch FROM d::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint,
      (extract(epoch FROM (d + 1)::timestamp AT TIME ZONE 'UTC') * 1000000)::bigint);
  END LOOP;
END $$;

-- Optional publication alteration: swap the renamed table for the new parent in
-- every publication that (by OID) still publishes it.
DO $$
DECLARE pub record;
BEGIN
  FOR pub IN
    SELECT p.pubname, p.pubviaroot
      FROM pg_publication p
      JOIN pg_publication_tables t ON t.pubname = p.pubname
      WHERE NOT p.puballtables
        AND t.schemaname = current_schema()
        AND t.tablename = 'cutover_events_legacy'
  LOOP
    EXECUTE format('ALTER PUBLICATION %I DROP TABLE %I', pub.pubname, 'cutover_events_legacy');
    IF pub.pubviaroot THEN
      EXECUTE format('ALTER PUBLICATION %I ADD TABLE %I', pub.pubname, 'cutover_events');
    ELSE
      -- a partitioned parent in such a publication is published per partition
      RAISE WARNING 'publication % does not publish_via_partition_root; not adding cutover_events',
        pub.pubname;
    END IF;
  END LOOP;
END $$;

COMMIT;
-- COMMIT, with no warnings
```

```sql
SELECT * FROM pg_publication_tables WHERE pubname = 'pgpart_stag_prod_pub';
-- partitioned_events and cutover_events; cutover_events_legacy is *not* listed
```

- Re-running only the last `DO` block changes nothing.
- **Robust without a publication:** in a scratch schema, create a plain
  `cutover_events` with `row_id bigserial PRIMARY KEY`, then run the whole
  migration block with `search_path` set to that schema. It must succeed without warnings:

  ```sql
  CREATE SCHEMA nopub_test; SET search_path TO nopub_test;
  CREATE TABLE cutover_events (row_id bigserial PRIMARY KEY, record_time bigint NOT NULL, payload text);
  -- run the 2b block, then
  DROP SCHEMA nopub_test CASCADE; SET search_path TO partition_test;
  ```

Insert post-cutover rows across several partitions:

```sql
INSERT INTO cutover_events (record_time, payload)
  SELECT (extract(epoch FROM t) * 1000000)::bigint, 'post-cutover ' || t
  FROM generate_series(current_date - 1 + interval '3 hours',
                       current_date + 1 + interval '21 hours', interval '6 hours') AS t;
-- INSERT 0 12

SELECT (SELECT count(*) FROM cutover_events_legacy) AS legacy,
       (SELECT count(*) FROM cutover_events) AS new,
       (SELECT min(row_id) FROM cutover_events) AS new_min_row_id;
-- 5, 12, 6 (the new parent continues the legacy row_id sequence)
```

Check BigQuery:

```sql
SELECT table_name FROM \`$BQ_DATASET\`.INFORMATION_SCHEMA.TABLES ORDER BY 1;
-- expect only partition_test_cutover_events and partition_test_partitioned_events;
-- no …_cutover_events_legacy and no per-partition table

SELECT starts_with(payload, 'pre-cutover') AS pre, count(*), min(row_id), max(row_id)
  FROM \`$BQ_DATASET.partition_test_cutover_events\` GROUP BY 1 ORDER BY 1;
-- expect false, 12, 6, 17 (all post-cutover rows) and
--        true, 5, 1, 5 (the pre-cutover rows, unchanged), in the same table

SELECT row_id, count(*) FROM \`$BQ_DATASET.partition_test_cutover_events\`
  GROUP BY row_id HAVING count(*) > 1;
-- expect no rows, empty output (meaning no duplicates)
```

- **Expected:** the stream stays `RUNNING`, with no errors in
  `gcloud datastream objects list`.
- **From real run**:
  - The Datastream didn't even notice anything changed about `cutover_events`. No backfill or stream errors.
  - The primary key changed from `(row_id)` to `(record_time, row_id)` in psql, but **the BQ table is still clustered by `row_id` only**.

## 3. Pruning by DDL

Dropping a partition, or the legacy table, is DDL. Logical replication emits
nothing for it, so the append-only BigQuery tables must not change.

```sql
DROP TABLE partitioned_events_p<yesterday>;   -- e.g. partitioned_events_p20261011
-- DROP TABLE
DROP TABLE cutover_events_p<yesterday>;
-- DROP TABLE
DROP TABLE cutover_events_legacy;             -- already out of the publication
-- DROP TABLE

SELECT count(*) FROM partitioned_events;
-- 10 (14 minus yesterday's 4)

SELECT count(*) FROM cutover_events;
-- 8 (12 minus yesterday's 4; the 5 pre-cutover rows went with the legacy table)
```

Wait a few minutes, then:

```sql
SELECT count(*) FROM \`$BQ_DATASET.partition_test_partitioned_events\`;
-- expect 14, unchanged from section 1

SELECT count(*) FROM \`$BQ_DATASET.partition_test_cutover_events\`;
-- expect 17, unchanged from section 2

SELECT datastream_metadata.change_type, count(*)
  FROM \`$BQ_DATASET.partition_test_partitioned_events\` GROUP BY 1;
-- expect INSERT, 14 (no DELETE)
```

- **Expected:** the stream is still `RUNNING`.

## 4. Read-only check for real clusters

Do **not** alter publications on real clusters; the legacy Datastreams run on them.
On clusters with the stag-prod stream enabled, check only:

```sql
SELECT pubname, pubviaroot FROM pg_publication;
-- update_history_datastream_stag_prod_pub should have t; see below if it has f
```

`bigquery-cloudsql.sh` sets `publish_via_partition_root` only when it **creates**
a publication. Its `ALTER PUBLICATION … SET TABLE` branch leaves the existing
setting alone. A `update_history_datastream_stag_prod_pub` created before
f56f841ab therefore still has `pubviaroot = f`. Datastream does not support
changing that setting on a publication a stream already uses; the stream fails
permanently. Such a cluster needs a new publication, slot and stream before the
cutover.

## Teardown

```bash
cncluster pulumi pg-partition-bq-test down
```

Deleting the stack drops the replication slot and publication first, then the
instance, and deletes the BigQuery dataset along with its contents.

Everything the stack and this procedure create carries `pgpart-pr7737`: as the
`testing` label where the resource takes labels, as the description of the
firewall rule, and as a network tag on the NAT VM. To look for leftovers (Cloud
Asset Inventory lags by a few minutes, and needs the Cloud Asset API):

```bash
gcloud asset search-all-resources --scope="projects/$CLOUDSDK_CORE_PROJECT" --query='pgpart-pr7737'
kubectl get pods -A -l testing=pgpart-pr7737
```

The temporary GCS bucket that `bigquery-cloudsql.sh` uses to create the
publication is not labelled, because the stack runs that script unchanged.
