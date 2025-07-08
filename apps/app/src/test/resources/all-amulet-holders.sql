-- Queries all parties that held Amulets up to a certain record time & migration ID.

DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

-- configuration parameters
SET as_of_record_time = PARSE_TIMESTAMP('%FT%TZ', '2025-06-26T00:00:00Z');
SET migration_id = 3;

SELECT DISTINCT
  JSON_VALUE(create_arguments, '$.record.fields[1].value.party') as owner
  FROM `da-cn-mainnet.mainnet_da2_scan.scan_sv_1_update_history_creates` c
  WHERE c.package_name = "splice-amulet"
    AND c.template_id_entity_name = "Amulet"
    AND (c.migration_id < migration_id OR (c.migration_id = migration_id AND c.created_at <= UNIX_MICROS(as_of_record_time)));
