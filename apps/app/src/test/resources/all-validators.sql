-- Queries all validators that held Validator Licenses up to a certain record time & migration ID, along with their first and latest validator license renewals.

DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

-- configuration parameters
SET as_of_record_time = PARSE_TIMESTAMP('%FT%TZ', '2025-07-15T00:00:00Z');
SET migration_id = 3;

SELECT DISTINCT
    JSON_VALUE(create_arguments, '$.record.fields[0].value.party') as validator_party,
    TIMESTAMP_MICROS(MAX(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, '$.record.fields[0].value.party'))) AS latest_validator_license,
    TIMESTAMP_MICROS(MIN(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, '$.record.fields[0].value.party'))) AS first_validator_license
  FROM `da-cn-mainnet.mainnet_da2_scan.scan_sv_1_update_history_creates` c
  WHERE c.package_name = "splice-amulet"
    AND c.template_id_module_name = "Splice.ValidatorLicense"
    AND c.template_id_entity_name = "ValidatorLicense"
    AND (c.migration_id < migration_id OR (c.migration_id = migration_id AND c.created_at <= UNIX_MICROS(as_of_record_time)));
