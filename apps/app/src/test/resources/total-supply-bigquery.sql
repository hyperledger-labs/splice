-- TODO (#18620) put this file somewhere that makes sense
DECLARE
  as_of_record_time timestamp;
DECLARE
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

CREATE OR REPLACE PROCEDURE
  experiment_dataset.sum_bignumeric_acs(OUT result bignumeric,
    path string,
    module_name string,
    entity_name string,
    as_of_record_time timestamp,
    migration_id int64) OPTIONS(strict_mode=TRUE,
    description='Find the ACS as of given time and sum bignumerics at path in the payload.')
BEGIN
DECLARE
  rt_micros int64;
SET
  rt_micros = UNIX_MICROS(as_of_record_time);
SET
  result = (
  SELECT
    COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, path))), 0)
  FROM
    experiment_dataset.creates c,
    experiment_dataset.transactions t
  WHERE
    NOT EXISTS (
    SELECT
      TRUE
    FROM
      experiment_dataset.exercises e,
      experiment_dataset.transactions t2
    WHERE
      e.update_row_id = t2.row_id
      AND (t2.migration_id < migration_id
        OR (t2.migration_id = migration_id
          AND t2.record_time <= rt_micros))
      AND e.consuming
      AND e.template_id_module_name = module_name
      AND e.template_id_entity_name = entity_name
      AND e.contract_id = c.contract_id)
    AND c.template_id_module_name = module_name
    AND c.template_id_entity_name = entity_name
    AND c.update_row_id = t.row_id -- we don't assume that t.row_id increases with record time
    AND (t.migration_id < migration_id
      OR (t.migration_id = migration_id
        AND t.record_time <= rt_micros)));
END
  ;

CREATE OR REPLACE PROCEDURE
  experiment_dataset.locked(OUT result bignumeric,
    as_of_record_time timestamp,
    migration_id int64) OPTIONS(strict_mode=TRUE,
    description='Total unspent but locked Amulet amount.')
BEGIN
DECLARE
  locked_amulet_amulet_amount_initialAmount_path string;
SET
  locked_amulet_amulet_amount_initialAmount_path = '$.record.fields[0].value.record.fields[2].value.record.fields[0].value.numeric';
CALL
  experiment_dataset.sum_bignumeric_acs(result,
    locked_amulet_amulet_amount_initialAmount_path,
    'Splice.Amulet',
    'LockedAmulet',
    as_of_record_time,
    migration_id);
END
  ;

CREATE OR REPLACE PROCEDURE
  experiment_dataset.unlocked(OUT result bignumeric,
    as_of_record_time timestamp,
    migration_id int64) OPTIONS(strict_mode=TRUE,
    description='Total unlocked, unspent Amulet.')
BEGIN
DECLARE
  amulet_amount_initialAmount_path string;
SET
  amulet_amount_initialAmount_path = '$.record.fields[2].value.record.fields[0].value.numeric';
CALL
  experiment_dataset.sum_bignumeric_acs(result,
    amulet_amount_initialAmount_path,
    'Splice.Amulet',
    'Amulet',
    as_of_record_time,
    migration_id);
END
  ;

CREATE OR REPLACE PROCEDURE
  experiment_dataset.unminted(OUT result bignumeric,
    as_of_record_time timestamp,
    migration_id int64) OPTIONS(strict_mode=TRUE,
    description='Amulet that was possible to mint, but was not minted.')
BEGIN
DECLARE
  unclaimedreward_amount string;
SET
  unclaimedreward_amount = '$.record.fields[1].value.numeric';
CALL
  experiment_dataset.sum_bignumeric_acs(result,
    unclaimedreward_amount,
    'Splice.Amulet',
    'UnclaimedReward',
    as_of_record_time,
    migration_id);
END
  ;

CREATE OR REPLACE PROCEDURE
  experiment_dataset.minted(OUT result bignumeric,
    as_of_record_time timestamp,
    migration_id int64) OPTIONS(strict_mode=TRUE,
    description='All Amulet that was ever minted.')
BEGIN
DECLARE
  TransferResult_summary,
  inputAppRewardAmount,
  inputValidatorRewardAmount,
  inputSvRewardAmount string;
SET
  TransferResult_summary = '$.record.fields[1].value.record.fields';
SET
  inputAppRewardAmount = TransferResult_summary || '[0].value.numeric';
SET
  inputValidatorRewardAmount = TransferResult_summary || '[1].value.numeric';
SET
  inputSvRewardAmount = TransferResult_summary || '[2].value.numeric';
SET
  result = (SELECT
  SUM(PARSE_BIGNUMERIC(JSON_VALUE(e.result, inputAppRewardAmount))
    + PARSE_BIGNUMERIC(JSON_VALUE(e.result, inputValidatorRewardAmount))
    + PARSE_BIGNUMERIC(JSON_VALUE(e.result, inputSvRewardAmount)))
FROM
  experiment_dataset.exercises e,
  experiment_dataset.transactions t
WHERE
  e.choice = 'AmuletRules_Transfer'
  AND e.template_id_module_name = 'Splice.AmuletRules'
  AND e.template_id_entity_name = 'AmuletRules'
  AND e.update_row_id = t.row_id -- we don't assume that t.row_id increases with record time
  AND (t.migration_id < migration_id
    OR (t.migration_id = migration_id
      AND t.record_time <= UNIX_MICROS(as_of_record_time))));
END
  ;

-- fees from a Splice.AmuletRules:TransferResult
CREATE TEMP FUNCTION
    transferresult_fees(tr_json string)
                       RETURNS bignumeric AS (-- .summary.holdingFees
    PARSE_BIGNUMERIC(JSON_VALUE(tr_json, '$.record.fields[1].value.record.fields[5].value.numeric'))
    -- .summary.senderChangeFee
    + PARSE_BIGNUMERIC(JSON_VALUE(tr_json, '$.record.fields[1].value.record.fields[7].value.numeric'))
    -- .summary.outputFees
    + (
    SELECT
      COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
    FROM
      UNNEST(JSON_QUERY_ARRAY(tr_json, '$.record.fields[1].value.record.fields[6].value.list.elements')) AS x));

CREATE TEMP FUNCTION
    result_burn(choice string,
                   result string)
               RETURNS bignumeric AS (CASE choice
      WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
    -- AmuletRules_BuyMemberTrafficResult
    PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[2].value.numeric')) -- .amuletPaid
    + PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[1].value.record.fields[5].value.numeric')) -- .summary.holdingFees
    + PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[1].value.record.fields[7].value.numeric')) -- .summary.senderChangeFee
      WHEN 'AmuletRules_Transfer' THEN -- Amulet Burnt in Amulet Transfers
    -- TransferResult
    -- .summary.holdingFees
    PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[1].value.record.fields[5].value.numeric'))
    -- .summary.senderChangeFee
    + PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[1].value.record.fields[7].value.numeric'))
    -- .summary.outputFees
    + (
    SELECT
      COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
    FROM
      UNNEST(JSON_QUERY_ARRAY(result, '$.record.fields[1].value.record.fields[6].value.list.elements')) AS x)
      WHEN 'AmuletRules_CreateTransferPreapproval' THEN PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[2].value.numeric')) -- .amuletPaid
    + transferresult_fees(JSON_QUERY(result, '$.record.fields[1].value')) -- .transferResult
      WHEN 'AmuletRules_CreateExternalPartySetupProposal' THEN PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[4].value.numeric')) -- .amuletPaid
    + transferresult_fees(JSON_QUERY(result, '$.record.fields[3].value')) -- .transferResult
      WHEN 'TransferPreapproval_Renew' THEN PARSE_BIGNUMERIC(JSON_VALUE(result, '$.record.fields[4].value.numeric')) -- .amuletPaid
    + transferresult_fees(JSON_QUERY(result, '$.record.fields[1].value')) -- .transferResult
      ELSE 0
  END
    );

CREATE OR REPLACE PROCEDURE
    experiment_dataset.burned(OUT result bignumeric,
                              as_of_record_time timestamp,
                              migration_id int64) OPTIONS(strict_mode=FALSE,
    description='Amulet burned via fees.')
BEGIN
SET result = (
    SELECT
        SUM(fees)
    FROM ((
              SELECT
                  SUM(result_burn(e.choice,
                                  e.result)) fees
              FROM
                  experiment_dataset.exercises e,
                  experiment_dataset.transactions t
              WHERE
                  ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                                 'AmuletRules_Transfer',
                                 'AmuletRules_CreateTransferPreapproval',
                                 'AmuletRules_CreateExternalPartySetupProposal')
                      AND e.template_id_entity_name = 'AmuletRules')
                      OR (e.choice = 'TransferPreapproval_Renew'
                          AND e.template_id_entity_name = 'TransferPreapproval'))
                AND e.template_id_module_name = 'Splice.AmuletRules'
                AND e.update_row_id = t.row_id -- we don't assume that t.row_id increases with record time
                AND (t.migration_id < migration_id
                  OR (t.migration_id = migration_id
                      AND t.record_time <= UNIX_MICROS(as_of_record_time))))
          UNION ALL (-- Purchasing ANS Entries
              SELECT
                  SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, '$.record.fields[2].value.record.fields[0].value.numeric'))) fees -- .amount.initialAmount
              FROM
                  experiment_dataset.exercises e,
                  experiment_dataset.creates c,
                  experiment_dataset.transactions t
              WHERE
                  ((e.choice = 'SubscriptionInitialPayment_Collect'
                      AND e.template_id_entity_name = 'SubscriptionInitialPayment'
                      AND c.contract_id = JSON_VALUE(e.result, '$.record.fields[2].value.contractId')) -- .amulet
                      OR (e.choice = 'SubscriptionPayment_Collect'
                          AND e.template_id_entity_name = 'SubscriptionPayment'
                          AND c.contract_id = JSON_VALUE(e.result, '$.record.fields[1].value.contractId'))) -- .amulet
                AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
                AND c.template_id_module_name = 'Splice.Amulet'
                AND c.template_id_entity_name = 'Amulet'
                AND e.update_row_id = t.row_id -- we don't assume that t.row_id increases with record time
                AND (t.migration_id < migration_id
                  OR (t.migration_id = migration_id
                      AND t.record_time <= UNIX_MICROS(as_of_record_time))))));
END;

-- using the functions
SET as_of_record_time = iso_timestamp('2025-03-10T00:00:00Z');
SET migration_id = 4;
CALL experiment_dataset.locked(locked, as_of_record_time, migration_id);
CALL experiment_dataset.unlocked(unlocked, as_of_record_time, migration_id);
CALL experiment_dataset.unminted(unminted, as_of_record_time, migration_id);
CALL experiment_dataset.minted(minted, as_of_record_time, migration_id);
CALL experiment_dataset.burned(burned, as_of_record_time, migration_id);
SET current_supply_total = locked + unlocked;
SET allowed_mint = unminted + minted;
SELECT
  locked locked,
  unlocked unlocked,
  current_supply_total current_supply_total,
  unminted unminted,
  minted minted,
  allowed_mint allowed_mint,
  burned burned;
