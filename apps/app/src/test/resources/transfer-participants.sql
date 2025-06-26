DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

CREATE TEMP FUNCTION
    iso_timestamp(iso8601_string string)
    RETURNS timestamp AS (PARSE_TIMESTAMP('%FT%TZ', iso8601_string));

SET as_of_record_time = iso_timestamp('2025-06-26T00:00:00Z');
SET migration_id = 3;

SELECT JSON_QUERY_ARRAY(e.argument, '$.record.fields[0].value.record.fields[3].value.list.elements')
FROM mainnet_da2_scan.scan_sv_1_update_history_exercises e
WHERE e.choice = 'AmuletRules_Transfer'
  AND e.template_id_module_name = 'Splice.AmuletRules'
  AND e.template_id_entity_name = 'AmuletRules'
  AND (e.migration_id < migration_id
    OR (e.migration_id = migration_id
        AND e.record_time <= UNIX_MICROS(as_of_record_time)))
LIMIT 100;
