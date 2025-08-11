-- Queries all parties that held Amulets up to a certain record time & migration ID, with the creation time of their most recently created Amulet.

DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

-- configuration parameters
SET as_of_record_time = PARSE_TIMESTAMP('%FT%TZ', '2025-07-15T00:00:00Z');
SET migration_id = 3;

SELECT
  JSON_VALUE(create_arguments, '$.record.fields[1].value.party') as owner,
  TIMESTAMP_MICROS(created_at) as latest_amulet_created
  FROM `da-cn-mainnet.mainnet_da2_scan.scan_sv_1_update_history_creates` c
  WHERE c.package_name = "splice-amulet"
    AND c.template_id_module_name = "Splice.Amulet"
    AND c.template_id_entity_name = "Amulet"
    AND (c.migration_id < migration_id OR (c.migration_id = migration_id AND c.created_at <= UNIX_MICROS(as_of_record_time)))
  QUALIFY ROW_NUMBER() OVER (PARTITION BY owner ORDER BY created_at DESC) = 1;
