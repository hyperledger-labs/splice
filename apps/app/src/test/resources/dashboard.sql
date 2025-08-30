-- Queries all validators that held Validator Licenses up to a certain record time & migration ID, along with their first and latest validator license renewals.

DECLARE
    active_after_record_time timestamp;
DECLARE
    active_up_to_record_time timestamp;
DECLARE
    validators int64;


CREATE TEMP FUNCTION validators(
    active_after_record_time timestamp,
    active_up_to_record_time timestamp
    ) RETURNS int64 AS ((
        SELECT
            COUNT( DISTINCT JSON_VALUE(create_arguments, '$.record.fields[0].value.party') ) as total_validators
            -- DISTINCT
            --     JSON_VALUE(create_arguments, '$.record.fields[0].value.party') as validator_party,
            --     TIMESTAMP_MICROS(MAX(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, '$.record.fields[0].value.party'))) AS latest_validator_license,
            --     TIMESTAMP_MICROS(MIN(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, '$.record.fields[0].value.party'))) AS first_validator_license
        FROM `da-cn-mainnet.mainnet_da2_scan.scan_sv_1_update_history_creates` c
        WHERE c.package_name = "splice-amulet"
            AND c.template_id_module_name = "Splice.ValidatorLicense"
            AND c.template_id_entity_name = "ValidatorLicense"
            AND c.created_at <= UNIX_MICROS(active_up_to_record_time)
            AND c.created_at > UNIX_MICROS(active_after_record_time)
    ));

SET active_after_record_time = PARSE_TIMESTAMP('%FT%TZ', @active_after_record_time);
SET active_up_to_record_time = PARSE_TIMESTAMP('%FT%TZ', @active_up_to_record_time);

SET validators = validators(active_after_record_time, active_up_to_record_time);

SELECT validators total_validators;
