// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  BIGNUMERIC,
  BOOL,
  BQArray,
  BQColumn,
  BQFunctionArgument,
  BQLogicalView,
  BQProcedure,
  BQScalarFunction,
  BQStruct,
  BQTable,
  BQTableFunction,
  FLOAT64,
  INT64,
  json,
  STRING,
  TIMESTAMP,
} from './bigQuery_functions_types';

/**
 * All reusable BigQuery functions are defined here.
 * Pulumi pushes them to BigQuery as part of deployment for prod clusters.
 * We also support codegen of sql statements that create these functions in BigQuery, which is currently used for
 * the integration test in ScanTotalSupplyBigQueryIntegrationTest.
 *
 * Note that the functions are parameterized with $$FUNCTIONS_DATASET$$, $$SCAN_DATASET$$ and $$DASHBOARDS_DATASET$$ placeholders that are replaced
 * by Pulumi and codegen, to point to the correct datasets. Any reference to a table in the scan dataset must use the
 * $$SCAN_DATASET$$ placeholder, e.g. `$$SCAN_DATASET$$.scan_sv_1_update_history_creates`. Similarly, all references to
 * another function must use the $$FUNCTIONS_DATASET$$ placeholder, e.g. `$$FUNCTIONS_DATASET$$.daml_record_path`.
 *
 * Note also that the functions are ordered, and each function may refer only to functions defined earlier.
 */

const as_of_args = [
  new BQFunctionArgument('as_of_record_time', TIMESTAMP),
  new BQFunctionArgument('migration_id', INT64),
];

const time_window_args = [
  new BQFunctionArgument('start_record_time', TIMESTAMP),
  new BQFunctionArgument('start_migration_id', INT64),
  new BQFunctionArgument('up_to_record_time', TIMESTAMP),
  new BQFunctionArgument('up_to_migration_id', INT64),
];

const rewardsStruct = new BQStruct([
  { name: 'appRewardAmount', type: BIGNUMERIC },
  { name: 'validatorRewardAmount', type: BIGNUMERIC },
  { name: 'svRewardAmount', type: BIGNUMERIC },
  { name: 'unclaimedActivityRecordAmount', type: BIGNUMERIC },
]);

const iso_timestamp = new BQScalarFunction(
  'iso_timestamp',
  [new BQFunctionArgument('iso8601_string', STRING)],
  TIMESTAMP,
  "PARSE_TIMESTAMP('%FT%TZ', iso8601_string)"
);

const daml_prim_path = new BQScalarFunction(
  'daml_prim_path',
  [new BQFunctionArgument('selector', STRING)],
  STRING,
  `
      CASE selector
        WHEN 'numeric' THEN '.numeric'
        WHEN 'contractId' THEN '.contractId'
        WHEN 'list' THEN '.list.elements'
        WHEN 'party' THEN '.party'
        WHEN 'int64' THEN '.int64'
        -- we treat records just like outer layer;
        -- see how paths start with '$.record'
        WHEN 'record' THEN ''
        ELSE ERROR('Unknown Daml primitive case: ' || selector)
      END
    `
);

const daml_record_path = new BQScalarFunction(
  'daml_record_path',
  [
    new BQFunctionArgument('field_indices', new BQArray(INT64)),
    new BQFunctionArgument('prim_selector', STRING),
  ],
  STRING,
  `
    -- Return a full JSON path to a nested Daml record field.  A field lookup like
    -- \`.x.y.z\` can be accessed as follows:
    -- 1. Find the record that defines \`x\`.
    -- 2. Find the 0-based index of \`x\` in that record, in order of its fields.
    --    For example, consider it the fourth field (index 3) for this example.
    -- 3. Next, move to the type of the \`x\` field, which should have \`y\`.
    -- 4. Repeat step (2) for \`y\` to find the next index.
    --    In this example, suppose it is the first field (index 0).
    -- 5. Repeat steps (3) and (4) for \`z\`.
    --    In this example, suppose it is the second field (index 1).
    -- 6. The first argument here is \`[3, 0, 1]\` for this example.
    -- 7. Finally, check the type of \`z\`; see \`daml_prim_path\` for a matching
    --    selector to pass here.

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
          \`$$FUNCTIONS_DATASET$$.daml_prim_path\`(prim_selector))

  `
);

const daml_record_numeric = new BQScalarFunction(
  'daml_record_numeric',
  [new BQFunctionArgument('daml_record', json), new BQFunctionArgument('path', new BQArray(INT64))],
  BIGNUMERIC,
  `PARSE_BIGNUMERIC(JSON_VALUE(daml_record,
        \`$$FUNCTIONS_DATASET$$.daml_record_path\`(path, 'numeric')))`
);

const in_time_window = new BQScalarFunction(
  'in_time_window',
  [
    new BQFunctionArgument('start_record_time', TIMESTAMP), // can be NULL for no lower bound
    new BQFunctionArgument('start_migration_id', INT64), // can be NULL for no lower bound
    new BQFunctionArgument('up_to_record_time', TIMESTAMP),
    new BQFunctionArgument('up_to_migration_id', INT64),
    new BQFunctionArgument('record_time', INT64),
    new BQFunctionArgument('migration_id', INT64),
  ],
  BOOL,
  `
    CASE
      WHEN start_record_time IS NULL AND start_migration_id IS NULL THEN
        (migration_id < up_to_migration_id
            OR (migration_id = up_to_migration_id
              AND record_time <= UNIX_MICROS(up_to_record_time)))
          AND record_time != -62135596800000000
      WHEN start_record_time IS NOT NULL AND start_migration_id IS NOT NULL THEN
        (migration_id > start_migration_id
            OR (migration_id = start_migration_id
              AND record_time > UNIX_MICROS(start_record_time)))
          AND (migration_id < up_to_migration_id
            OR (migration_id = up_to_migration_id
              AND record_time <= UNIX_MICROS(up_to_record_time)))
          AND record_time != -62135596800000000
      ELSE ERROR('in_time_window: start_record_time and start_migration_id must be both NULL or both NOT NULL')
    END
  `
);

const up_to_time = new BQScalarFunction(
  'up_to_time',
  [
    new BQFunctionArgument('up_to_record_time', TIMESTAMP),
    new BQFunctionArgument('up_to_migration_id', INT64),
    new BQFunctionArgument('record_time', INT64),
    new BQFunctionArgument('migration_id', INT64),
  ],
  BOOL,
  `
    \`$$FUNCTIONS_DATASET$$.in_time_window\`(NULL, NULL, up_to_record_time, up_to_migration_id, record_time, migration_id)
  `
);

const migration_id_at_time = new BQScalarFunction(
  'migration_id_at_time',
  [new BQFunctionArgument('as_of_record_time', TIMESTAMP)],
  INT64,
  `
    -- Given a record time, find the latest migration ID that was active at that time. Takes the lowest ID that has updates
    -- after the given time, therefore if the timestamp is during a migration, it will return the older migration ID.
    -- If no updates exist after the given time, returns the migration id of the last update.
    IFNULL
    (
      -- Try to find the lowest migration ID that has updates after the given time.
      (SELECT
        MIN(migration_id)
      FROM
        ((SELECT record_time, migration_id FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\`) UNION ALL
         (SELECT record_time, migration_id FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\`))
      WHERE record_time > UNIX_MICROS(as_of_record_time)),
      -- If none exists, return the migration ID of the last update.
      (SELECT migration_id FROM
        (
          (SELECT record_time, migration_id FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\`) UNION ALL
          (SELECT record_time, migration_id FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\`)
        ) ORDER BY record_time DESC LIMIT 1)
    )
  `
);

const sum_bignumeric_acs = new BQScalarFunction(
  'sum_bignumeric_acs',
  [
    new BQFunctionArgument('path', new BQArray(INT64)),
    new BQFunctionArgument('module_name', STRING),
    new BQFunctionArgument('entity_name', STRING),
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
  BIGNUMERIC,
  `
    -- Find the ACS as of given time and sum bignumerics at path in the payload.
    (SELECT
      COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments,
        \`$$FUNCTIONS_DATASET$$.daml_record_path\`(path, 'numeric')))), 0)
    FROM
      \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
    WHERE
      NOT EXISTS (
      SELECT
        TRUE
      FROM
        \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\` e
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
      AND \`$$FUNCTIONS_DATASET$$.up_to_time\`(as_of_record_time, migration_id,
            c.record_time, c.migration_id))
  `
);

const locked = new BQScalarFunction(
  'locked',
  as_of_args,
  BIGNUMERIC,
  `
    \`$$FUNCTIONS_DATASET$$.sum_bignumeric_acs\`(
      -- (LockedAmulet) .amulet.amount.initialAmount
      [0, 2, 0],
      'Splice.Amulet',
      'LockedAmulet',
      as_of_record_time,
      migration_id)
  `
);

const unlocked = new BQScalarFunction(
  'unlocked',
  as_of_args,
  BIGNUMERIC,
  `
    \`$$FUNCTIONS_DATASET$$.sum_bignumeric_acs\`(
      -- (Amulet) .amount.initialAmount
      [2, 0],
      'Splice.Amulet',
      'Amulet',
      as_of_record_time,
      migration_id)
  `
);

const unminted = new BQScalarFunction(
  'unminted',
  as_of_args,
  BIGNUMERIC,
  `
    \`$$FUNCTIONS_DATASET$$.sum_bignumeric_acs\`(
      -- (UnclaimedReward) .amount
      [1],
      'Splice.Amulet',
      'UnclaimedReward',
      as_of_record_time,
      migration_id)
  `
);

const TransferSummary_minted = new BQScalarFunction(
  'TransferSummary_minted',
  [new BQFunctionArgument('tr_json', json)],
  rewardsStruct,
  `
    STRUCT(
      \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [0]) AS appRewardAmount,
      \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [1]) AS validatorRewardAmount,
      \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [2]) AS svRewardAmount,
      IFNULL(\`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [11]), 0) AS unclaimedActivityRecordAmount -- (was added only in Splice 0.4.4)
    )
  `
);

const choice_result_TransferSummary = new BQScalarFunction(
  'choice_result_TransferSummary',
  [new BQFunctionArgument('choice', STRING), new BQFunctionArgument('result', json)],
  json,
  `
    CASE choice
      WHEN 'AmuletRules_CreateExternalPartySetupProposal'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([3, 1], 'record'))
      WHEN 'AmuletRules_CreateTransferPreapproval'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 1], 'record'))
      WHEN 'AmuletRules_BuyMemberTraffic'
        -- .summary
        THEN JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'record'))
      WHEN 'AmuletRules_Transfer'
        -- .summary
        THEN JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'record'))
      WHEN 'TransferPreapproval_Renew'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 1], 'record'))
      ELSE ERROR('no TransferSummary for this choice: ' || choice)
    END
  `
);

const minted = new BQScalarFunction(
  'minted',
  time_window_args,
  rewardsStruct,
  `
    (SELECT
        STRUCT(
          COALESCE(SUM(\`$$FUNCTIONS_DATASET$$.TransferSummary_minted\`(
                    \`$$FUNCTIONS_DATASET$$.choice_result_TransferSummary\`(e.choice, e.result)).appRewardAmount),
                  0) AS appRewardAmount,
          COALESCE(SUM(\`$$FUNCTIONS_DATASET$$.TransferSummary_minted\`(
                    \`$$FUNCTIONS_DATASET$$.choice_result_TransferSummary\`(e.choice, e.result)).validatorRewardAmount),
                  0) AS validatorRewardAmount,
          COALESCE(SUM(\`$$FUNCTIONS_DATASET$$.TransferSummary_minted\`(
                    \`$$FUNCTIONS_DATASET$$.choice_result_TransferSummary\`(e.choice, e.result)).svRewardAmount),
                  0) AS svRewardAmount,
          COALESCE(SUM(\`$$FUNCTIONS_DATASET$$.TransferSummary_minted\`(
                    \`$$FUNCTIONS_DATASET$$.choice_result_TransferSummary\`(e.choice, e.result)).unclaimedActivityRecordAmount),
                  0) AS unclaimedActivityRecordAmount
        )
      FROM
        \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\` e
      WHERE
        -- all the choices that can take coupons as input, and thus mint amulets based on them.
        ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                      'AmuletRules_Transfer',
                      'AmuletRules_CreateTransferPreapproval',
                      'AmuletRules_CreateExternalPartySetupProposal')
            AND e.template_id_entity_name = 'AmuletRules')
            OR (e.choice = 'TransferPreapproval_Renew'
                AND e.template_id_entity_name = 'TransferPreapproval'))
        AND e.template_id_module_name = 'Splice.AmuletRules'
        AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(
              start_record_time, start_migration_id,
              up_to_record_time, up_to_migration_id,
              e.record_time, e.migration_id))
  `
);

const transferresult_fees = new BQScalarFunction(
  'transferresult_fees',
  [new BQFunctionArgument('tr_json', json)],
  BIGNUMERIC,
  `
    -- .summary.holdingFees
    PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 5], 'numeric')))
    -- .summary.senderChangeFee
    + PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 7], 'numeric')))
    + (SELECT COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
      FROM
        UNNEST(JSON_QUERY_ARRAY(tr_json,
                  -- .summary.outputFees
                  \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 6], 'list'))) AS x)
  `
);

const result_burn = new BQScalarFunction(
  'result_burn',
  [new BQFunctionArgument('choice', STRING), new BQFunctionArgument('result', json)],
  BIGNUMERIC,
  `
    CASE choice
      WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
        -- AmuletRules_BuyMemberTrafficResult
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
        + PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 5], 'numeric'))) -- .summary.holdingFees
        + PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1, 7], 'numeric'))) -- .summary.senderChangeFee
      WHEN 'AmuletRules_Transfer' THEN -- Amulet Burnt in Amulet Transfers
        -- TransferResult
        \`$$FUNCTIONS_DATASET$$.transferresult_fees\`(result)
      WHEN 'AmuletRules_CreateTransferPreapproval' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
        + \`$$FUNCTIONS_DATASET$$.transferresult_fees\`(JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'record'))) -- .transferResult
      WHEN 'AmuletRules_CreateExternalPartySetupProposal' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
        + \`$$FUNCTIONS_DATASET$$.transferresult_fees\`(JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([3], 'record'))) -- .transferResult
      WHEN 'TransferPreapproval_Renew' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
        + \`$$FUNCTIONS_DATASET$$.transferresult_fees\`(JSON_QUERY(result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'record'))) -- .transferResult
      ELSE ERROR('Unknown choice for result_burn: ' || choice)
    END
  `
);

const burned = new BQScalarFunction(
  'burned',
  time_window_args,
  BIGNUMERIC,
  `
    (SELECT SUM(fees)
      FROM ((
                SELECT
                    SUM(\`$$FUNCTIONS_DATASET$$.result_burn\`(e.choice,
                                              e.result)) fees
                FROM
                    \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\` e
                WHERE
                    ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                                  'AmuletRules_Transfer',
                                  'AmuletRules_CreateTransferPreapproval',
                                  'AmuletRules_CreateExternalPartySetupProposal')
                        AND e.template_id_entity_name = 'AmuletRules')
                        OR (e.choice = 'TransferPreapproval_Renew'
                            AND e.template_id_entity_name = 'TransferPreapproval'))
                  AND e.template_id_module_name = 'Splice.AmuletRules'
                  AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(
                          start_record_time, start_migration_id,
                          up_to_record_time, up_to_migration_id,
                          e.record_time, e.migration_id))
            UNION ALL (-- Purchasing ANS Entries
                SELECT
                    SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([2, 0], 'numeric')))) fees -- .amount.initialAmount
                FROM
                    \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\` e,
                    \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
                WHERE
                    ((e.choice = 'SubscriptionInitialPayment_Collect'
                        AND e.template_id_entity_name = 'SubscriptionInitialPayment'
                        AND c.contract_id = JSON_VALUE(e.result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([2], 'contractId'))) -- .amulet
                        OR (e.choice = 'SubscriptionPayment_Collect'
                            AND e.template_id_entity_name = 'SubscriptionPayment'
                            AND c.contract_id = JSON_VALUE(e.result, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'contractId')))) -- .amulet
                  AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
                  AND c.template_id_module_name = 'Splice.Amulet'
                  AND c.template_id_entity_name = 'Amulet'
                  AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(
                          start_record_time, start_migration_id,
                          up_to_record_time, up_to_migration_id,
                          e.record_time, e.migration_id)
                  AND c.record_time != -62135596800000000)))
  `
);

const amulet_holders = new BQTableFunction(
  'amulet_holders',
  as_of_args,
  [new BQColumn('owner', STRING), new BQColumn('latest_amulet_created', TIMESTAMP)],
  `
    SELECT
      JSON_VALUE(create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1], 'party')) as owner,
      TIMESTAMP_MICROS(created_at) as latest_amulet_created
      FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
      WHERE c.package_name = "splice-amulet"
        AND c.template_id_module_name = "Splice.Amulet"
        AND c.template_id_entity_name = "Amulet"
        AND \`$$FUNCTIONS_DATASET$$.up_to_time\`(as_of_record_time, migration_id, c.record_time, c.migration_id)
      QUALIFY ROW_NUMBER() OVER (PARTITION BY owner ORDER BY created_at DESC) = 1
  `
);

const num_amulet_holders = new BQScalarFunction(
  'num_amulet_holders',
  as_of_args,
  INT64,
  `
    (SELECT COUNT(*) as num_amulet_holders FROM \`$$FUNCTIONS_DATASET$$.amulet_holders\`(as_of_record_time, migration_id))
  `
);

const all_validators = new BQTableFunction(
  'all_validators',
  as_of_args,
  [
    new BQColumn('validator_operator_party', STRING),
    new BQColumn('latest_validator_license', TIMESTAMP),
    new BQColumn('first_validator_license', TIMESTAMP),
  ],
  `
    SELECT DISTINCT
        JSON_VALUE(create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([0], 'party')) as validator_operator_party,
        TIMESTAMP_MICROS(MAX(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([0], 'party')))) AS latest_validator_license,
        TIMESTAMP_MICROS(MIN(created_at) OVER (PARTITION BY JSON_VALUE(create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([0], 'party')))) AS first_validator_license
      FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
      WHERE c.package_name = "splice-amulet"
        AND c.template_id_module_name = "Splice.ValidatorLicense"
        AND c.template_id_entity_name = "ValidatorLicense"
        AND \`$$FUNCTIONS_DATASET$$.up_to_time\`(as_of_record_time, migration_id, c.record_time, c.migration_id)
  `
);

const num_active_validators = new BQScalarFunction(
  'num_active_validators',
  as_of_args,
  INT64,
  `
    (SELECT COUNT(*) as num_active_validators
      FROM \`$$FUNCTIONS_DATASET$$.all_validators\`(as_of_record_time, migration_id) v
      WHERE v.latest_validator_license > TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR))
  `
);

const one_day_updates = new BQTableFunction(
  'one_day_updates',
  as_of_args,
  [new BQColumn('update_id', STRING), new BQColumn('record_time', INT64)],
  `
    -- All update IDs over a period of 24 hours. Since the data might be up to 4 hours stale, we take a window of 4-28 hours ago.
    SELECT DISTINCT(update_id), record_time FROM (
        SELECT
          update_id,
          record_time
        FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\` e
        WHERE \`$$FUNCTIONS_DATASET$$.in_time_window\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 28 HOUR), migration_id, TIMESTAMP_SUB(as_of_record_time, INTERVAL 4 HOUR), migration_id, e.record_time, e.migration_id)
      UNION ALL
        SELECT
          update_id,
          record_time
        FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
        WHERE \`$$FUNCTIONS_DATASET$$.in_time_window\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 28 HOUR), migration_id, TIMESTAMP_SUB(as_of_record_time, INTERVAL 4 HOUR), migration_id, c.record_time, c.migration_id)
    )
  `
);

const average_tps = new BQScalarFunction(
  'average_tps',
  as_of_args,
  FLOAT64,
  `
    (SELECT COUNT(*) / 86400.0
      FROM \`$$FUNCTIONS_DATASET$$.one_day_updates\`(as_of_record_time, migration_id)
    )
  `
);

const peak_tps = new BQScalarFunction(
  'peak_tps',
  as_of_args,
  FLOAT64,
  `
    (SELECT
      MAX(events_per_minute) / 60.0
    FROM (
      SELECT
        COUNT(*) as events_per_minute
      FROM \`$$FUNCTIONS_DATASET$$.one_day_updates\`(as_of_record_time, migration_id)
      GROUP BY TIMESTAMP_TRUNC(TIMESTAMP_MICROS(record_time), MINUTE)
    )
  )
  `
);

const coin_price = new BQScalarFunction(
  'coin_price',
  time_window_args,
  new BQStruct([
    { name: 'minPrice', type: BIGNUMERIC },
    { name: 'maxPrice', type: BIGNUMERIC },
    { name: 'avgPrice', type: BIGNUMERIC },
  ]),
  `
    (SELECT AS
      STRUCT
        MIN(\`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(c.create_arguments, [2])),
        MAX(\`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(c.create_arguments, [2])),
        AVG(\`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(c.create_arguments, [2]))
    FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
      WHERE template_id_entity_name = 'SummarizingMiningRound'
      AND c.template_id_module_name = 'Splice.Round'
      AND package_name = 'splice-amulet'
      AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(
        start_record_time, start_migration_id,
        up_to_record_time, up_to_migration_id,
        c.record_time, c.migration_id
    ))
  `
);

const latest_round = new BQScalarFunction(
  'latest_round',
  as_of_args,
  INT64,
  `
    (SELECT
        CAST(JSON_VALUE(c.create_arguments, \`$$FUNCTIONS_DATASET$$.daml_record_path\`([1,0], 'int64')) AS INT64)
      FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_creates\` c
          WHERE template_id_entity_name = 'SummarizingMiningRound'
          AND c.template_id_module_name = 'Splice.Round'
          AND package_name = 'splice-amulet'
          AND \`$$FUNCTIONS_DATASET$$.up_to_time\`(
            as_of_record_time, migration_id,
            c.record_time, c.migration_id)
          ORDER BY c.record_time DESC LIMIT 1)
  `
);

const all_dashboard_stats = new BQTableFunction(
  'all_dashboard_stats',
  as_of_args,
  [
    new BQColumn('as_of_record_time', TIMESTAMP),
    new BQColumn('migration_id', INT64),
    new BQColumn('locked', BIGNUMERIC),
    new BQColumn('unlocked', BIGNUMERIC),
    new BQColumn('current_supply_total', BIGNUMERIC),
    new BQColumn('unminted', BIGNUMERIC),
    new BQColumn('daily_mint_app_rewards', BIGNUMERIC),
    new BQColumn('daily_mint_validator_rewards', BIGNUMERIC),
    new BQColumn('daily_mint_sv_rewards', BIGNUMERIC),
    new BQColumn('daily_mint_unclaimed_activity_records', BIGNUMERIC),
    new BQColumn('daily_burn', BIGNUMERIC),
    new BQColumn('num_amulet_holders', INT64),
    new BQColumn('num_active_validators', INT64),
    new BQColumn('average_tps', FLOAT64),
    new BQColumn('peak_tps', FLOAT64),
    new BQColumn('daily_min_coin_price', BIGNUMERIC),
    new BQColumn('daily_max_coin_price', BIGNUMERIC),
    new BQColumn('daily_avg_coin_price', BIGNUMERIC),
  ],
  `
    SELECT
      as_of_record_time,
      migration_id,
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) as locked,
      \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as unlocked,
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) + \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as current_supply_total,
      \`$$FUNCTIONS_DATASET$$.unminted\`(as_of_record_time, migration_id) as unminted,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).appRewardAmount
          AS daily_mint_app_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).validatorRewardAmount
          AS daily_mint_validator_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time, migration_id).svRewardAmount
          AS daily_mint_sv_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).unclaimedActivityRecordAmount
          AS daily_mint_unclaimed_activity_records,
      IFNULL(
        \`$$FUNCTIONS_DATASET$$.burned\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id),
        0) AS daily_burn,
      \`$$FUNCTIONS_DATASET$$.num_amulet_holders\`(as_of_record_time, migration_id) as num_amulet_holders,
      \`$$FUNCTIONS_DATASET$$.num_active_validators\`(as_of_record_time, migration_id) as num_active_validators,
      IFNULL(\`$$FUNCTIONS_DATASET$$.average_tps\`(as_of_record_time, migration_id), 0.0) as average_tps,
      IFNULL(\`$$FUNCTIONS_DATASET$$.peak_tps\`(as_of_record_time, migration_id), 0.0) as peak_tps,
      \`$$FUNCTIONS_DATASET$$.coin_price\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).minPrice
          AS daily_min_coin_price,
      \`$$FUNCTIONS_DATASET$$.coin_price\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).maxPrice
          AS daily_max_coin_price,
      \`$$FUNCTIONS_DATASET$$.coin_price\`(
            TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR),
            \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(TIMESTAMP_SUB(as_of_record_time, INTERVAL 24 HOUR)),
            as_of_record_time,
            migration_id).avgPrice
          AS daily_avg_coin_price
  `
);

const all_finance_stats = new BQTableFunction(
  'all_finance_stats',
  as_of_args,
  [
    new BQColumn('as_of_record_time', TIMESTAMP),
    new BQColumn('migration_id', INT64),
    new BQColumn('locked', BIGNUMERIC),
    new BQColumn('unlocked', BIGNUMERIC),
    new BQColumn('current_supply_total', BIGNUMERIC),
    new BQColumn('unminted', BIGNUMERIC),
    new BQColumn('total_mint_app_rewards', BIGNUMERIC),
    new BQColumn('total_mint_validator_rewards', BIGNUMERIC),
    new BQColumn('total_mint_sv_rewards', BIGNUMERIC),
    new BQColumn('total_mint_unclaimed_activity_records', BIGNUMERIC),
    new BQColumn('total_burn', BIGNUMERIC),
    new BQColumn('num_amulet_holders', INT64),
    new BQColumn('num_active_validators', INT64),
    new BQColumn('latest_round', INT64),
  ],
  `
    SELECT
      as_of_record_time,
      migration_id,
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) as locked,
      \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as unlocked,
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) + \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as current_supply_total,
      \`$$FUNCTIONS_DATASET$$.unminted\`(as_of_record_time, migration_id) as unminted,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            NULL,
            NULL,
            as_of_record_time,
            migration_id).appRewardAmount
          AS total_mint_app_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            NULL,
            NULL,
            as_of_record_time,
            migration_id).validatorRewardAmount
          AS total_mint_validator_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            NULL,
            NULL,
            as_of_record_time, migration_id).svRewardAmount
          AS total_mint_sv_rewards,
      \`$$FUNCTIONS_DATASET$$.minted\`(
            NULL,
            NULL,
            as_of_record_time,
            migration_id).unclaimedActivityRecordAmount
          AS total_mint_unclaimed_activity_records,
      IFNULL(
        \`$$FUNCTIONS_DATASET$$.burned\`(
            NULL,
            NULL,
            as_of_record_time,
            migration_id),
        0) AS total_burn,
      \`$$FUNCTIONS_DATASET$$.num_amulet_holders\`(as_of_record_time, migration_id) as num_amulet_holders,
      \`$$FUNCTIONS_DATASET$$.num_active_validators\`(as_of_record_time, migration_id) as num_active_validators,
      \`$$FUNCTIONS_DATASET$$.latest_round\`(as_of_record_time, migration_id) as latest_round

  `
);

/**
 * Functions and procedures for the dashboards dataset: ones that are used to actually populate the data in the tables used by the dashboards.
 */

const all_days_since_genesis = new BQTableFunction(
  'all_days_since_genesis',
  [],
  [new BQColumn('as_of_record_time', TIMESTAMP)],
  `
    -- Generate all days since genesis (first record time in the scan dataset) until today.
    SELECT
      TIMESTAMP(day) as as_of_record_time
    FROM
      UNNEST(
        GENERATE_DATE_ARRAY(
          -- DATE(
          --   TIMESTAMP_MICROS((SELECT MIN(record_time) FROM \`$$SCAN_DATASET$$.scan_sv_1_update_history_exercises\`))
          --),
          -- TODO(DACH-NY/canton-network-internal#1461): for now we compute only last 60 days until we confirm costs, and will
          -- backfill to genesis later.
          DATE(TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 60 DAY)),
          CURRENT_DATE
        )
      ) as day
  `
);

const days_with_missing_stats = new BQTableFunction(
  'days_with_missing_stats',
  [],
  [new BQColumn('as_of_record_time', TIMESTAMP)],
  `
    -- Find all days since genesis for which we do not have a stats entry at all, or its lacking some fields.
    SELECT as_of_record_time
      FROM \`$$DASHBOARDS_DATASET$$.all_days_since_genesis\`()
      EXCEPT DISTINCT
        SELECT
          as_of_record_time
          FROM \`$$DASHBOARDS_DATASET$$.dashboards-data\`
          WHERE
            locked IS NOT NULL
            AND unlocked IS NOT NULL
            AND current_supply_total IS NOT NULL
            AND unminted IS NOT NULL
            AND daily_mint_app_rewards IS NOT NULL
            AND daily_mint_validator_rewards IS NOT NULL
            AND daily_mint_sv_rewards IS NOT NULL
            AND daily_mint_unclaimed_activity_records IS NOT NULL
            AND daily_burn IS NOT NULL
            AND num_amulet_holders IS NOT NULL
            AND num_active_validators IS NOT NULL
            AND average_tps IS NOT NULL
            AND peak_tps IS NOT NULL
            AND daily_min_coin_price IS NOT NULL
            AND daily_max_coin_price IS NOT NULL
            AND daily_avg_coin_price IS NOT NULL
    `
);

const fill_all_stats = new BQProcedure(
  'fill_all_stats',
  [],
  `
    FOR t IN
      (SELECT * FROM \`$$DASHBOARDS_DATASET$$.days_with_missing_stats\`())
    DO
      DELETE FROM \`$$DASHBOARDS_DATASET$$.dashboards-data\` WHERE as_of_record_time = t.as_of_record_time;

      INSERT INTO \`$$DASHBOARDS_DATASET$$.dashboards-data\`
        SELECT * FROM \`$$FUNCTIONS_DATASET$$.all_dashboard_stats\`(t.as_of_record_time, \`$$FUNCTIONS_DATASET$$.migration_id_at_time\`(t.as_of_record_time));

    END FOR;
  `
);

/**
 * Views for the dashboards dataset: auxiliary computations used by the dashboards.
 */

const monthly_burn = new BQLogicalView(
  'monthly_burn',
  `
    SELECT
        as_of_record_time as monthly_as_of_record_time,
        SUM(daily_burn) OVER (ORDER BY as_of_record_time ROWS BETWEEN 30 PRECEDING AND CURRENT ROW) as monthly_burn,
        SUM(daily_burn * daily_avg_coin_price) OVER (ORDER BY as_of_record_time ROWS BETWEEN 30 PRECEDING AND CURRENT ROW) as monthly_burn_usd
      FROM
        \`$$DASHBOARDS_DATASET$$.dashboards-data\`
  `
);

const daily_unminted = new BQLogicalView(
  'daily_unminted',
  `
    SELECT
        as_of_record_time as daily_as_of_record_time,
        unminted - LAG(unminted) OVER (
            ORDER BY as_of_record_time
        ) AS daily_unminted
    FROM
        \`$$DASHBOARDS_DATASET$$.dashboards-data\`
  `
);

const all_data = new BQLogicalView(
  'all_data',
  `
    SELECT
      *,
      DATE_SUB(DATE(computed.as_of_record_time), INTERVAL 1 DAY) AS up_to_date
    FROM
        \`$$DASHBOARDS_DATASET$$.dashboards-data\` computed
        JOIN \`$$DASHBOARDS_DATASET$$.monthly_burn\` monthly_burn
          ON computed.as_of_record_time = monthly_burn.monthly_as_of_record_time
        JOIN \`$$DASHBOARDS_DATASET$$.daily_unminted\` daily_unminted
          ON computed.as_of_record_time = daily_unminted.daily_as_of_record_time
  `
);

export const allScanFunctions = [
  iso_timestamp,
  daml_prim_path,
  daml_record_path,
  daml_record_numeric,
  in_time_window,
  up_to_time,
  migration_id_at_time,
  sum_bignumeric_acs,
  locked,
  unlocked,
  unminted,
  TransferSummary_minted,
  choice_result_TransferSummary,
  minted,
  transferresult_fees,
  result_burn,
  burned,
  amulet_holders,
  num_amulet_holders,
  all_validators,
  num_active_validators,
  one_day_updates,
  average_tps,
  peak_tps,
  coin_price,
  latest_round,
  all_dashboard_stats,
  all_finance_stats,
];

export const computedDataTable = new BQTable('dashboards-data', [
  new BQColumn('as_of_record_time', TIMESTAMP),
  new BQColumn('migration_id', INT64),
  new BQColumn('locked', BIGNUMERIC),
  new BQColumn('unlocked', BIGNUMERIC),
  new BQColumn('current_supply_total', BIGNUMERIC),
  new BQColumn('unminted', BIGNUMERIC),
  new BQColumn('daily_mint_app_rewards', BIGNUMERIC),
  new BQColumn('daily_mint_validator_rewards', BIGNUMERIC),
  new BQColumn('daily_mint_sv_rewards', BIGNUMERIC),
  new BQColumn('daily_mint_unclaimed_activity_records', BIGNUMERIC),
  new BQColumn('daily_burn', BIGNUMERIC),
  new BQColumn('num_amulet_holders', INT64),
  new BQColumn('num_active_validators', INT64),
  new BQColumn('average_tps', FLOAT64),
  new BQColumn('peak_tps', FLOAT64),
  new BQColumn('daily_min_coin_price', BIGNUMERIC),
  new BQColumn('daily_max_coin_price', BIGNUMERIC),
  new BQColumn('daily_avg_coin_price', BIGNUMERIC),
]);

export const allDashboardFunctions = [
  /* Functions and routines */
  all_days_since_genesis,
  days_with_missing_stats,
  fill_all_stats,
  /* Views */
  monthly_burn,
  daily_unminted,
  all_data,
];
