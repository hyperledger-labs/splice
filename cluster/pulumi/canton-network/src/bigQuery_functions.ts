// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  BIGNUMERIC,
  BOOL,
  BQArray,
  BQBasicType,
  BQColumn,
  BQFunctionArgument,
  BQScalarFunction,
  BQTableFunction,
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
 * Note that the functions are parameterized with $$FUNCTIONS_DATASET$$ and $$SCAN_DATASET$$ placeholders that are replaced
 * by Pulumi and codegen, to point to the correct datasets. Any reference to a table in the scan dataset must use the
 * $$SCAN_DATASET$$ placeholder, e.g. `$$SCAN_DATASET$$.scan_sv_1_update_history_creates`. Similarly, all references to
 * another function must use the $$FUNCTIONS_DATASET$$ placeholder, e.g. `$$FUNCTIONS_DATASET$$.daml_record_path`.
 *
 * Note also that the functions are ordered, and each function may refer only to functions defined earlier.
 */

const iso_timestamp = new BQScalarFunction(
  'iso_timestamp',
  [new BQFunctionArgument('iso8601_string', STRING)],
  new BQBasicType('TIMESTAMP'),
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
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id_arg', INT64),
    new BQFunctionArgument('record_time', INT64),
    new BQFunctionArgument('migration_id', INT64),
  ],
  BOOL,
  `
    (migration_id < migration_id_arg
        OR (migration_id = migration_id_arg
          AND record_time <= UNIX_MICROS(as_of_record_time)))
      AND record_time != -62135596800000000
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
      AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(as_of_record_time, migration_id,
            c.record_time, c.migration_id))
  `
);

const locked = new BQScalarFunction(
  'locked',
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
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
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
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
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
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
  BIGNUMERIC,
  `
    \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [0]) -- .inputAppRewardAmount
    + \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [1]) -- .inputValidatorRewardAmount
    + \`$$FUNCTIONS_DATASET$$.daml_record_numeric\`(tr_json, [2]) -- .inputSvRewardAmount

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
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
  BIGNUMERIC,
  `
    (SELECT
        COALESCE(SUM(\`$$FUNCTIONS_DATASET$$.TransferSummary_minted\`(
                  \`$$FUNCTIONS_DATASET$$.choice_result_TransferSummary\`(e.choice, e.result))),
                0)
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
        AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(as_of_record_time, migration_id,
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
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id_arg', INT64),
  ],
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
                  AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(as_of_record_time, migration_id_arg,
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
                  AND \`$$FUNCTIONS_DATASET$$.in_time_window\`(as_of_record_time, migration_id_arg,
                        e.record_time, e.migration_id)
                  AND c.record_time != -62135596800000000)))
  `
);

const total_supply = new BQTableFunction(
  'total_supply',
  [
    new BQFunctionArgument('as_of_record_time', TIMESTAMP),
    new BQFunctionArgument('migration_id', INT64),
  ],
  [
    new BQColumn('locked', BIGNUMERIC),
    new BQColumn('unlocked', BIGNUMERIC),
    new BQColumn('current_supply_total', BIGNUMERIC),
    new BQColumn('unminted', BIGNUMERIC),
    new BQColumn('minted', BIGNUMERIC),
    new BQColumn('allowed_mint', BIGNUMERIC),
    new BQColumn('burned', BIGNUMERIC),
  ],
  `
    SELECT
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) as locked,
      \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as unlocked,
      \`$$FUNCTIONS_DATASET$$.locked\`(as_of_record_time, migration_id) + \`$$FUNCTIONS_DATASET$$.unlocked\`(as_of_record_time, migration_id) as current_supply_total,
      \`$$FUNCTIONS_DATASET$$.unminted\`(as_of_record_time, migration_id) as unminted,
      \`$$FUNCTIONS_DATASET$$.minted\`(as_of_record_time, migration_id) as minted,
      \`$$FUNCTIONS_DATASET$$.minted\`(as_of_record_time, migration_id) + \`$$FUNCTIONS_DATASET$$.unminted\`(as_of_record_time, migration_id) as allowed_mint,
      \`$$FUNCTIONS_DATASET$$.burned\`(as_of_record_time, migration_id) as burned
  `
);

export const allFunctions = [
  iso_timestamp,
  daml_prim_path,
  daml_record_path,
  daml_record_numeric,
  in_time_window,
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
  total_supply,
];
