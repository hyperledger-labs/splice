DECLARE
    as_of_record_time timestamp;
DECLARE
    migration_id int64;

-- configuration parameters
SET as_of_record_time = PARSE_TIMESTAMP('%FT%TZ', '2025-06-26T00:00:00Z');
SET migration_id = 3;

CREATE TEMP FUNCTION daml_prim_path(selector string)
    RETURNS string AS (
  CASE selector
    WHEN 'numeric' THEN '.numeric'
    WHEN 'contractId' THEN '.contractId'
    WHEN 'list' THEN '.list.elements'
    WHEN 'party' THEN '.party'
    -- we treat records just like outer layer;
    -- see how paths start with `$.record`
    WHEN 'record' THEN ''
    ELSE ERROR('Unknown Daml primitive case: ' || selector)
  END
);

CREATE TEMP FUNCTION daml_record_path(
    field_indices array<int64>,
    prim_selector string
  ) RETURNS string AS (
  CONCAT('$',
         -- you cannot use SELECT in a BigQuery JSONPath, even indirectly
         CASE ARRAY_LENGTH(field_indices)
           WHEN 0 THEN ''
           WHEN 1 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value')
           WHEN 2 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value',
                              '.record.fields[', CAST(field_indices[1] AS STRING), '].value')
           WHEN 3 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value',
                              '.record.fields[', CAST(field_indices[1] AS STRING), '].value',
                              '.record.fields[', CAST(field_indices[2] AS STRING), '].value')
           ELSE ERROR('Unsupported number of field indices: ' || ARRAY_LENGTH(field_indices))
         END,
         daml_prim_path(prim_selector))
);

CREATE TEMP FUNCTION TransferOutputs_receivers(TransferOutput_array array<json>)
  RETURNS array<string> AS (ARRAY(
    SELECT JSON_VALUE(TransferOutput,
                      -- .receiver
                      daml_record_path([0], 'party'))
    FROM UNNEST(TransferOutput_array) AS TransferOutput));

WITH daml_Transfer_jsons AS (
       SELECT JSON_VALUE(e.argument,
                         -- .transfer.sender
                         '$.record.fields[0].value.record.fields[0].value.party')
              Transfer_sender,
              JSON_QUERY_ARRAY(e.argument,
                               -- .transfer.outputs[]
                               daml_record_path([0, 3], 'list'))
              TransferOutput_array
       FROM mainnet_da2_scan.scan_sv_1_update_history_exercises e
       WHERE e.choice = 'AmuletRules_Transfer'
         AND e.template_id_module_name = 'Splice.AmuletRules'
         AND e.template_id_entity_name = 'AmuletRules'
         AND (e.migration_id < migration_id
           OR (e.migration_id = migration_id
               AND e.record_time <= UNIX_MICROS(as_of_record_time)))
       LIMIT 100) -- TODO (DACH-NY/canton-network-internal#703) remove limit for full test
  SELECT DISTINCT party_id
  FROM daml_Transfer_jsons src
       INNER JOIN UNNEST(
           ARRAY_CONCAT(
             [src.Transfer_sender],
             TransferOutputs_receivers(src.TransferOutput_array)))
         AS party_id;
