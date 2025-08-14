DECLARE
  start_record_time,
  as_of_record_time timestamp;
DECLARE
  start_migration_id,
  migration_id,
  migration_id_arg int64;

CREATE TEMP FUNCTION
  iso_timestamp(iso8601_string string)
  RETURNS timestamp AS (PARSE_TIMESTAMP('%FT%TZ', iso8601_string));

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

-- the most common JSON selection in this file
CREATE TEMP FUNCTION daml_record_numeric(
    daml_record json,
    path array<int64>
  ) RETURNS bignumeric AS (
  PARSE_BIGNUMERIC(JSON_VALUE(daml_record,
      daml_record_path(path, 'numeric'))));

CREATE TEMP FUNCTION in_time_window(
    start_record_time timestamp,
    start_migration_id int64,
    as_of_record_time timestamp,
    migration_id_arg int64,
    record_time int64,
    migration_id int64
  ) RETURNS boolean AS (
  (migration_id < migration_id_arg
    OR (migration_id = migration_id_arg
      AND record_time <= UNIX_MICROS(as_of_record_time)))
  AND (migration_id > start_migration_id
       OR (migration_id = start_migration_id
           AND record_time >= UNIX_MICROS(start_record_time)))
);

-- fees from a Splice.AmuletRules:TransferResult
CREATE TEMP FUNCTION transferresult_fees(tr_json json)
    RETURNS bignumeric AS (
  -- .summary.holdingFees
  PARSE_BIGNUMERIC(JSON_VALUE(tr_json, daml_record_path([1, 5], 'numeric')))
  -- .summary.senderChangeFee
  + PARSE_BIGNUMERIC(JSON_VALUE(tr_json, daml_record_path([1, 7], 'numeric')))
  + (SELECT COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
     FROM
       UNNEST(JSON_QUERY_ARRAY(tr_json,
                -- .summary.outputFees
                daml_record_path([1, 6], 'list'))) AS x));

CREATE TEMP FUNCTION result_traffic_purchase(choice string, result json)
    RETURNS bignumeric AS (
  CASE choice
    WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
      -- AmuletRules_BuyMemberTrafficResult
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([2], 'numeric'))) -- .amuletPaid
    ELSE 0
  END);

CREATE TEMP FUNCTION result_burn(choice string, result json)
    RETURNS bignumeric AS (
  CASE choice
    WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
      -- AmuletRules_BuyMemberTrafficResult, less .amuletPaid
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([1, 5], 'numeric'))) -- .summary.holdingFees
      + PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([1, 7], 'numeric'))) -- .summary.senderChangeFee
    WHEN 'AmuletRules_Transfer' THEN -- Amulet Burnt in Amulet Transfers
      -- TransferResult
      transferresult_fees(result)
    WHEN 'AmuletRules_CreateTransferPreapproval' THEN
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([2], 'numeric'))) -- .amuletPaid
      + transferresult_fees(JSON_QUERY(result, daml_record_path([1], 'record'))) -- .transferResult
    WHEN 'AmuletRules_CreateExternalPartySetupProposal' THEN
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([4], 'numeric'))) -- .amuletPaid
      + transferresult_fees(JSON_QUERY(result, daml_record_path([3], 'record'))) -- .transferResult
    WHEN 'TransferPreapproval_Renew' THEN
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([4], 'numeric'))) -- .amuletPaid
      + transferresult_fees(JSON_QUERY(result, daml_record_path([1], 'record'))) -- .transferResult
    ELSE ERROR('Unknown choice for result_burn: ' || choice)
  END);

SET start_record_time = iso_timestamp('2025-05-14T00:00:00Z');
SET start_migration_id = 2;
SET as_of_record_time = iso_timestamp('2025-08-14T00:00:00Z');
SET migration_id = 3;
SET migration_id_arg = migration_id;

SELECT SUM(purchase_paid) purchase_burns, SUM(fees) non_purchase_burns
  FROM ((
            SELECT
                SUM(result_traffic_purchase(e.choice, e.result)) purchase_paid,
                SUM(result_burn(e.choice,
                                e.result)) fees
            FROM
                mainnet_da2_scan.scan_sv_1_update_history_exercises e
            WHERE
                ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                               'AmuletRules_Transfer',
                               'AmuletRules_CreateTransferPreapproval',
                               'AmuletRules_CreateExternalPartySetupProposal')
                    AND e.template_id_entity_name = 'AmuletRules')
                    OR (e.choice = 'TransferPreapproval_Renew'
                        AND e.template_id_entity_name = 'TransferPreapproval'))
              AND e.template_id_module_name = 'Splice.AmuletRules'
              AND in_time_window(start_record_time, start_migration_id,
                      as_of_record_time, migration_id_arg,
                      e.record_time, e.migration_id))
        UNION ALL (-- Purchasing ANS Entries
            SELECT
                0 purchase_paid,
                SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, daml_record_path([2, 0], 'numeric')))) fees -- .amount.initialAmount
            FROM
                mainnet_da2_scan.scan_sv_1_update_history_exercises e,
                mainnet_da2_scan.scan_sv_1_update_history_creates c
            WHERE
                ((e.choice = 'SubscriptionInitialPayment_Collect'
                    AND e.template_id_entity_name = 'SubscriptionInitialPayment'
                    AND c.contract_id = JSON_VALUE(e.result, daml_record_path([2], 'contractId'))) -- .amulet
                    OR (e.choice = 'SubscriptionPayment_Collect'
                        AND e.template_id_entity_name = 'SubscriptionPayment'
                        AND c.contract_id = JSON_VALUE(e.result, daml_record_path([1], 'contractId')))) -- .amulet
              AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
              AND c.template_id_module_name = 'Splice.Amulet'
              AND c.template_id_entity_name = 'Amulet'
              AND in_time_window(start_record_time, start_migration_id,
                    as_of_record_time, migration_id_arg,
                    e.record_time, e.migration_id)
              AND c.record_time != -62135596800000000));
