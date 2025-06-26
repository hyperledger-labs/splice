DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

-- configuration parameters
SET as_of_record_time = PARSE_TIMESTAMP('%FT%TZ', '2025-06-26T00:00:00Z');
SET migration_id = 3;

WITH daml_TransferOutput_jsons AS (
       SELECT JSON_QUERY_ARRAY(e.argument,
                               -- .transfer.outputs[]
                               '$.record.fields[0].value.record.fields[3].value.list.elements')
              TransferOutput_array
       FROM mainnet_da2_scan.scan_sv_1_update_history_exercises e
       WHERE e.choice = 'AmuletRules_Transfer'
         AND e.template_id_module_name = 'Splice.AmuletRules'
         AND e.template_id_entity_name = 'AmuletRules'
         AND (e.migration_id < migration_id
           OR (e.migration_id = migration_id
               AND e.record_time <= UNIX_MICROS(as_of_record_time)))
       LIMIT 100) -- TODO (DACH-NY/canton-network-internal#703) remove limit for full test
  SELECT DISTINCT JSON_VALUE(TransferOutput,
                             -- .receiver
                             '$.record.fields[0].value.party') party_id
  FROM daml_TransferOutput_jsons
       INNER JOIN UNNEST(daml_TransferOutput_jsons.TransferOutput_array) AS TransferOutput;
