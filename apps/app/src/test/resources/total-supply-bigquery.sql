-- TODO (DACH-NY/canton-network-internal#362) put this file somewhere that makes sense
DECLARE
  as_of_record_time timestamp;
DECLARE
  migration_id_ok,
  migration_id int64;
DECLARE
  locked,
  unlocked,
  unminted,
  minted,
  current_supply_total,
  allowed_mint,
  burned bignumeric;

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

-- Return a full JSON path to a nested Daml record field.  A field lookup like
-- `.x.y.z` can be accessed as follows:
-- 1. Find the record that defines `x`.
-- 2. Find the 0-based index of `x` in that record, in order of its fields.
--    For example, consider it the fourth field (index 3) for this example.
-- 3. Next, move to the type of the `x` field, which should have `y`.
-- 4. Repeat step (2) for `y` to find the next index.
--    In this example, suppose it is the first field (index 0).
-- 5. Repeat steps (3) and (4) for `z`.
--    In this example, suppose it is the second field (index 1).
-- 6. The first argument here is `[3, 0, 1]` for this example.
-- 7. Finally, check the type of `z`; see `daml_prim_path` for a matching
--    selector to pass here.
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
    as_of_record_time timestamp,
    migration_id_arg int64,
    record_time int64,
    migration_id int64
  ) RETURNS boolean AS (
  (migration_id < migration_id_arg
    OR (migration_id = migration_id_arg
      AND record_time <= UNIX_MICROS(as_of_record_time)))
  AND record_time != -62135596800000000
);

-- Find the ACS as of given time and sum bignumerics at path in the payload.
CREATE TEMP FUNCTION sum_bignumeric_acs(
    path array<int64>,
    module_name string,
    entity_name string,
    as_of_record_time timestamp,
    migration_id int64
  ) RETURNS bignumeric AS ((
  SELECT
    COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments,
      daml_record_path(path, 'numeric')))), 0)
  FROM
    mainnet_da2_scan.scan_sv_1_update_history_creates c
  WHERE
    NOT EXISTS (
    SELECT
      TRUE
    FROM
      mainnet_da2_scan.scan_sv_1_update_history_exercises e
    WHERE
      (e.migration_id < migration_id
        OR (e.migration_id = migration_id
          AND e.record_time <= UNIX_MICROS(as_of_record_time)))
      AND e.consuming
      AND e.template_id_module_name = module_name
      AND e.template_id_entity_name = entity_name
      AND e.contract_id = c.contract_id)
    AND c.template_id_module_name = module_name
    AND c.template_id_entity_name = entity_name
    AND in_time_window(as_of_record_time, migration_id,
          c.record_time, c.migration_id)));


-- Total unspent but locked Amulet amount.
CREATE TEMP FUNCTION locked(
    as_of_record_time timestamp,
    migration_id int64
  ) RETURNS bignumeric AS (
  sum_bignumeric_acs(
    -- (LockedAmulet) .amulet.amount.initialAmount
    [0, 2, 0],
    'Splice.Amulet',
    'LockedAmulet',
    as_of_record_time,
    migration_id));


-- Total unlocked, unspent Amulet.
CREATE TEMP FUNCTION unlocked(
    as_of_record_time timestamp,
    migration_id int64
  ) RETURNS bignumeric AS (
  sum_bignumeric_acs(
    -- (Amulet) .amount.initialAmount
    [2, 0],
    'Splice.Amulet',
    'Amulet',
    as_of_record_time,
    migration_id));


-- Amulet that was possible to mint, but was not minted.
CREATE TEMP FUNCTION unminted(
    as_of_record_time timestamp,
    migration_id int64
  ) RETURNS bignumeric AS (
  sum_bignumeric_acs(
    -- (UnclaimedReward) .amount
    [1],
    'Splice.Amulet',
    'UnclaimedReward',
    as_of_record_time,
    migration_id));


CREATE TEMP FUNCTION TransferSummary_minted(tr_json json)
    RETURNS bignumeric AS (
  daml_record_numeric(tr_json, [0]) -- .inputAppRewardAmount
  + daml_record_numeric(tr_json, [1]) -- .inputValidatorRewardAmount
  + daml_record_numeric(tr_json, [2]) -- .inputSvRewardAmount
);

-- A choice's TransferSummary, if that choice is filtered by `minted`.
CREATE TEMP FUNCTION choice_result_TransferSummary(choice string, result json)
    RETURNS json AS (
  CASE choice
    WHEN 'AmuletRules_CreateExternalPartySetupProposal'
      -- .transferResult.summary
      THEN JSON_QUERY(result, daml_record_path([3, 1], 'record'))
    WHEN 'AmuletRules_CreateTransferPreapproval'
      -- .transferResult.summary
      THEN JSON_QUERY(result, daml_record_path([1, 1], 'record'))
    WHEN 'AmuletRules_BuyMemberTraffic'
      -- .summary
      THEN JSON_QUERY(result, daml_record_path([1], 'record'))
    WHEN 'AmuletRules_Transfer'
      -- .summary
      THEN JSON_QUERY(result, daml_record_path([1], 'record'))
    WHEN 'TransferPreapproval_Renew'
      -- .transferResult.summary
      THEN JSON_QUERY(result, daml_record_path([1, 1], 'record'))
    ELSE ERROR('no TransferSummary for this choice: ' || choice)
  END);

-- All Amulet that was ever minted.
CREATE TEMP FUNCTION minted(
    as_of_record_time timestamp,
    migration_id int64
  ) RETURNS bignumeric AS ((
  SELECT
    COALESCE(SUM(TransferSummary_minted(
               choice_result_TransferSummary(e.choice, e.result))),
             0)
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
    AND in_time_window(as_of_record_time, migration_id,
          e.record_time, e.migration_id)));


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

CREATE TEMP FUNCTION result_burn(choice string, result json)
    RETURNS bignumeric AS (
  CASE choice
    WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
      -- AmuletRules_BuyMemberTrafficResult
      PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([2], 'numeric'))) -- .amuletPaid
      + PARSE_BIGNUMERIC(JSON_VALUE(result, daml_record_path([1, 5], 'numeric'))) -- .summary.holdingFees
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

-- Amulet burned via fees.
CREATE TEMP FUNCTION burned(
    as_of_record_time timestamp,
    migration_id_arg int64
  ) RETURNS bignumeric AS ((
  SELECT SUM(fees)
  FROM ((
            SELECT
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
              AND in_time_window(as_of_record_time, migration_id_arg,
                      e.record_time, e.migration_id))
        UNION ALL (-- Purchasing ANS Entries
            SELECT
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
              AND in_time_window(as_of_record_time, migration_id_arg,
                    e.record_time, e.migration_id)
              AND c.record_time != -62135596800000000))));


-- using the functions
SET as_of_record_time = iso_timestamp('2025-07-01T00:00:00Z');
SET migration_id = 3;
SET migration_id_ok = migration_id;

WITH SplitAndBurning_and_Transfer_nodes AS (
    SELECT e.choice choice, e.result result, e.child_event_ids child_event_ids
    FROM mainnet_da2_scan.scan_sv_1_update_history_exercises e
    WHERE ((e.choice = 'SubscriptionInitialPayment_Collect'
        AND e.template_id_entity_name = 'SubscriptionInitialPayment')
        OR (e.choice = 'SubscriptionPayment_Collect'
            AND e.template_id_entity_name = 'SubscriptionPayment'
            ))
      AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
      AND in_time_window(as_of_record_time, migration_id_ok,
                         e.record_time, e.migration_id))
SELECT b.choice parent_choice, e2.choice child_choice,
       timestamp_micros(e2.record_time) record_time
FROM SplitAndBurning_and_Transfer_nodes b,
     UNNEST(JSON_VALUE_ARRAY(b.child_event_ids)) AS child_event_id,
     mainnet_da2_scan.scan_sv_1_update_history_exercises e2
WHERE e2.event_id = child_event_id
  AND e2.record_time != -62135596800000000
--  AND e2.choice = 'AmuletRules_Transfer'
LIMIT 50;

SELECT
    e.choice choice,
    TransferSummary_minted(
            choice_result_TransferSummary(e.choice, e.result)) minted
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
  AND in_time_window(as_of_record_time, migration_id,
                     e.record_time, e.migration_id)
GROUP BY e.choice;

SET locked = locked(as_of_record_time, migration_id);
SET unlocked = unlocked(as_of_record_time, migration_id);
SET unminted = unminted(as_of_record_time, migration_id);
SET minted = minted(as_of_record_time, migration_id);
SET burned = burned(as_of_record_time, migration_id);
SET current_supply_total = locked + unlocked;
--SET allowed_mint = unminted + minted;


SELECT choice, fees
  FROM ((
            SELECT
                e.choice choice, SUM(result_burn(e.choice,
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
              AND in_time_window(as_of_record_time, migration_id_ok,
                      e.record_time, e.migration_id)
            GROUP BY e.choice)
        UNION ALL (-- Purchasing ANS Entries
            SELECT
                e.choice choice, SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, daml_record_path([2, 0], 'numeric')))) fees -- .amount.initialAmount
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
              AND in_time_window(as_of_record_time, migration_id_ok,
                    e.record_time, e.migration_id)
              AND c.record_time != -62135596800000000
              GROUP BY e.choice));

SELECT
  locked locked,
  unlocked unlocked,
  current_supply_total current_supply_total,
  unminted unminted,
  minted minted,
  allowed_mint allowed_mint,
  burned burned;
