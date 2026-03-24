CREATE VIEW ACS AS SELECT * FROM read_json_auto('data/*_ACS_*.jsonl.zstd', compression='zstd', sample_size=200000);
SELECT create_arguments->>'owner' as owner FROM ACS WHERE template_id LIKE '%Splice.Amulet:Amulet' AND owner = 'v-10-user-6::12205f5c982b8d9a02c56d5db5494ec8a829241b560f75e5724f8709d27e20da1204'  LIMIT 30;

CREATE TABLE ACS AS
  SELECT * FROM read_json_auto('data/2026-02-01T00_00_00Z-Migration-7-2026-02-02T00_00_00Z_ACS_0.jsonl.zstd', compression='zstd', sample_size=500000);


CREATE TABLE ACS AS SELECT * FROM read_json_auto(
    'data/*_ACS_*.jsonl.zstd',
    union_by_name=true,
    compression='zstd',
    sample_size=1000000
);

CREATE TABLE updates AS SELECT * FROM read_json_auto(
      'data/*_updates_*.jsonl.zstd',
      union_by_name=true, compression='zstd', sample_size=1000
  );


WITH flattened_updates AS (
    SELECT
        *,
        unnest(map_entries(events_by_id)) AS event
    FROM updates
)
SELECT
    f.record_time,
    f.event.key AS event_id,
    f.event.value.contract_id AS contract_id
FROM flattened_updates f
WHERE f.event.value.choice = 'Archive' LIMIT 1;



WITH flattened_updates AS (
    SELECT
        *,
        unnest(map_entries(events_by_id)) AS event
    FROM updates
)
SELECT
    f.record_time,
    f.event.key AS event_id,
    f.event.value.contract_id AS contract_id,
    f.event.value.create_arguments.amount.initialAmount,
    f.event.value.create_arguments.owner AS owner
FROM flattened_updates f
WHERE f.event.value.event_type = 'created_event'
  AND f.event.value.template_id LIKE '%Splice.Amulet:Amulet'
  AND f.event.value.create_arguments.owner IS NOT NULL
  AND (f.event.value.create_arguments.owner)::VARCHAR = '"v-48-user-3::12205799cf4130cf4ed268dc5bba01441ed67acfc80a546af11363d9399d05a2631c"';




All unarchived Amulet contracts for the owner as of 2026-02-02T20:00:00Z:

WITH flattened_updates AS (
      SELECT
          *, unnest(map_entries(events_by_id)) AS event
      FROM updates
  ),
  created_contracts AS (
      SELECT
          f.event.value.contract_id AS contract_id,
          f.event.value.create_arguments.amount.initialAmount AS amount,
          f.event.value.create_arguments.owner AS owner,
          f.record_time AS created_at
      FROM flattened_updates f
      WHERE f.event.value.event_type = 'created_event'
        AND f.event.value.template_id LIKE '%Splice.Amulet:Amulet'
        AND (f.event.value.create_arguments.owner)::VARCHAR = '"v-48-user-3::12205799cf4130cf4ed268dc5bba01441ed67acfc80a546af11363d9399d05a2631c"' AND f.record_time::TIMESTAMP <= '2026-02-02T20:00:00Z'::TIMESTAMP
  ),
  archived_contracts AS (
      SELECT
          f.event.value.contract_id AS contract_id, f.record_time AS archived_time
      FROM flattened_updates f
      WHERE f.event.value.choice = 'Archive' AND f.record_time::TIMESTAMP <= '2026-02-02T20:00:00Z'::TIMESTAMP
  )
  SELECT
      c.*
  FROM created_contracts c
  LEFT JOIN archived_contracts a ON c.contract_id = a.contract_id
  WHERE a.contract_id IS NULL;


=====>
    contract_id = 008442946bda453f389440fbd046e2f963e3689f7204429accaf1c1caa192dd494ca121220f927bc8f15c120a4123bc9cd67ba6bc8835dae9948ef2218fce053d67675fc34
    amount = "176140.1903865200"
    owner = "v-48-user-3::12205799cf4130cf4ed268dc5bba01441ed67acfc80a546af11363d9399d05a2631c"
    created_at = 2026-02-02T19:57:49.734283Z

(only one contract, so no need to sum anything)


Sum of all unarchived amulet contracts as of 2026-02-02T20:00:00Z:

WITH flattened_updates AS (
      SELECT
          *, unnest(map_entries(events_by_id)) AS event
      FROM updates
  ),
  created_contracts AS (
      SELECT
          f.event.value.contract_id AS contract_id,
          f.event.value.create_arguments.amount.initialAmount AS amount,
          f.event.value.create_arguments.owner AS owner,
          f.record_time AS created_at
      FROM flattened_updates f
      WHERE f.event.value.event_type = 'created_event'
        AND f.event.value.template_id LIKE '%Splice.Amulet:Amulet'
        AND f.record_time::TIMESTAMP <= '2026-02-02T20:00:0Z'::TIMESTAMP
  ),
  archived_contracts AS (
      SELECT
          f.event.value.contract_id AS contract_id, f.record_time AS archived_time
      FROM flattened_updates f
      WHERE f.event.value.choice = 'Archive' AND f.record_time::TIMESTAMP <= '2026-02-02T20:00:00Z'::TIMESTAMP
  )
  SELECT
      SUM(c.amount::DECIMAL(38,9))
  FROM created_contracts c
  LEFT JOIN archived_contracts a ON c.contract_id = a.contract_id
  WHERE a.contract_id IS NULL;


====>  sum(CAST(c.amount AS DECIMAL(38,9))) = 60374889058.705162460
