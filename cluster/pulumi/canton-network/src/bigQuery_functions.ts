import * as gcp from '@pulumi/gcp';

abstract class BQType {
  abstract toPulumi(): any;
  abstract toSql(): string;
}

class BQBasicType extends BQType {
  private readonly type: string;
  public constructor(type: string) {
    super();
    this.type = type;
  }
  public toPulumi(): any {
    return { typeKind: this.type };
  }

  public toSql(): string {
    return this.type;
  }
}

const STRING = new BQBasicType('STRING');
const INT64 = new BQBasicType('INT64');
const TIMESTAMP = new BQBasicType('TIMESTAMP');
const BIGNUMERIC = new BQBasicType('BIGNUMERIC');
const json = new BQBasicType('JSON'); // JSON is a reserved word in TypeScript
const BOOL = new BQBasicType('BOOL');


class BQArray extends BQType {
  private readonly elementType: BQType;
  public constructor(elementType: BQType) {
    super();
    this.elementType = elementType;
  }
  public toPulumi(): any {
    return { typeKind: "ARRAY", arrayElementType: this.elementType.toPulumi() };
  }

  public toSql(): string {
    return `ARRAY<${this.elementType.toSql()}>`;
  }
}

class BQFunctionArgument {
  private readonly name: string;
  private readonly type: BQType;

  public constructor(name: string, type: BQType) {
    this.name = name;
    this.type = type;
  }

  public toPulumi(): gcp.types.input.bigquery.RoutineArgument {
    return {
      name: this.name,
      dataType: JSON.stringify(this.type.toPulumi()),
    };
  }

  public toSql(): string {
    return `${this.name} ${this.type.toSql()}`;
  }
}

abstract class BQFunction {
  protected readonly name: string;
  protected readonly arguments: BQFunctionArgument[];
  protected readonly definitionBody: string;

  protected constructor(name: string, args: BQFunctionArgument[], definitionBody: string) {
    this.name = name;
    this.arguments = args;
    this.definitionBody = definitionBody;
  }
  public abstract toPulumi(dataset: gcp.bigquery.Dataset): gcp.bigquery.Routine;
  public abstract toSql(dataset: string): string;
}

class BQScalarFunction extends BQFunction {
  private readonly returnType: BQType;

  public constructor(name: string, args: BQFunctionArgument[], returnType: BQType, definitionBody: string) {
    super(name, args, definitionBody);
    this.returnType = returnType;
  }

  public toPulumi(dataset: gcp.bigquery.Dataset): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(this.name, {
      datasetId: dataset.datasetId,
      routineId: this.name,
      routineType: 'SCALAR_FUNCTION',
      language: 'SQL',
      definitionBody: this.definitionBody,
      arguments: this.arguments.map(arg => (
        arg.toPulumi()
      )),
      returnType: JSON.stringify(this.returnType.toPulumi()),
    });
  }

  public toSql(dataset: string): string {
    return `
      CREATE OR REPLACE FUNCTION ${dataset}.${this.name}(
        ${this.arguments.map(arg => arg.toSql()).join(',\n        ')}
      )
      RETURNS ${this.returnType.toSql()}
      AS (
      ${this.definitionBody}
      );
    `;
  }
}

class BQColumn {
  private readonly name: string;
  private readonly type: BQType;

  public constructor(name: string, type: BQType) {
    this.name = name;
    this.type = type;
  }

  public toPulumi(): any {
    return {
      name: this.name,
      type: this.type.toPulumi(),
    };
  }

  public toSql(): string {
    return `${this.name} ${this.type.toSql()}`;
  }
}

class BQTableFunction extends BQFunction {
  private readonly returnTableType: BQColumn[];

  public constructor(name: string, args: BQFunctionArgument[], returnTableType: BQColumn[], definitionBody: string) {
    super(name, args, definitionBody);
    this.returnTableType = returnTableType;
  }

  public toPulumi(dataset: gcp.bigquery.Dataset): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(this.name, {
      datasetId: dataset.datasetId,
      routineId: this.name,
      routineType: 'TABLE_VALUED_FUNCTION',
      language: 'SQL',
      definitionBody: this.definitionBody,
      arguments: this.arguments.map(arg => (
        arg.toPulumi()
      )),
      returnTableType: JSON.stringify({
        columns: this.returnTableType.map(col => col.toPulumi()),
      }),
    });
  }

  public toSql(dataset: string): string {
    return `
      CREATE OR REPLACE TABLE FUNCTION ${dataset}.${this.name}(
        ${this.arguments.map(arg => arg.toSql()).join(',\n        ')}
      )
      RETURNS TABLE<
        ${this.returnTableType.map(col => col.toSql()).join(',\n        ')}
      >
      AS (
      ${this.definitionBody}
      );
    `;
  }
}

const iso_timestamp = new BQScalarFunction(
  'iso_timestamp',
  [
    new BQFunctionArgument('iso8601_string', STRING),
  ],
  new BQBasicType('TIMESTAMP'),
  "PARSE_TIMESTAMP('%FT%TZ', iso8601_string)",
)

const daml_prim_path = new BQScalarFunction(
  'daml_prim_path',
  [
    new BQFunctionArgument('selector', STRING),
  ],
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
    `,
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
          \`da-cn-scratchnet.functions.daml_prim_path\`(prim_selector))

  `
);

const daml_record_numeric = new BQScalarFunction(
  'daml_record_numeric',
  [
    new BQFunctionArgument('daml_record', json),
    new BQFunctionArgument('path', new BQArray(INT64))
  ],
  BIGNUMERIC,
  `PARSE_BIGNUMERIC(JSON_VALUE(daml_record,
        \`da-cn-scratchnet.functions.daml_record_path\`(path, 'numeric')))`
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
        \`da-cn-scratchnet.functions.daml_record_path\`(path, 'numeric')))), 0)
    FROM
      \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_creates\` c
    WHERE
      NOT EXISTS (
      SELECT
        TRUE
      FROM
        \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
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
      AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id,
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
    \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
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
    \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
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
    \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
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
  [
    new BQFunctionArgument('tr_json', json),
  ],
  BIGNUMERIC,
  `
    \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [0]) -- .inputAppRewardAmount
    + \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [1]) -- .inputValidatorRewardAmount
    + \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [2]) -- .inputSvRewardAmount

  `
);

const choice_result_TransferSummary = new BQScalarFunction(
  'choice_result_TransferSummary',
  [
    new BQFunctionArgument('choice', STRING),
    new BQFunctionArgument('result', json),
  ],
  json,
  `
    CASE choice
      WHEN 'AmuletRules_CreateExternalPartySetupProposal'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([3, 1], 'record'))
      WHEN 'AmuletRules_CreateTransferPreapproval'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 1], 'record'))
      WHEN 'AmuletRules_BuyMemberTraffic'
        -- .summary
        THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))
      WHEN 'AmuletRules_Transfer'
        -- .summary
        THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))
      WHEN 'TransferPreapproval_Renew'
        -- .transferResult.summary
        THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 1], 'record'))
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
        COALESCE(SUM(\`da-cn-scratchnet.functions.TransferSummary_minted\`(
                  \`da-cn-scratchnet.functions.choice_result_TransferSummary\`(e.choice, e.result))),
                0)
      FROM
        \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
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
        AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id,
              e.record_time, e.migration_id))
  `
);

const transferresult_fees = new BQScalarFunction(
  'transferresult_fees',
  [
    new BQFunctionArgument('tr_json', json),
  ],
  BIGNUMERIC,
  `
    -- .summary.holdingFees
    PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`da-cn-scratchnet.functions.daml_record_path\`([1, 5], 'numeric')))
    -- .summary.senderChangeFee
    + PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`da-cn-scratchnet.functions.daml_record_path\`([1, 7], 'numeric')))
    + (SELECT COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
      FROM
        UNNEST(JSON_QUERY_ARRAY(tr_json,
                  -- .summary.outputFees
                  \`da-cn-scratchnet.functions.daml_record_path\`([1, 6], 'list'))) AS x)
  `
);

const result_burn = new BQScalarFunction(
  'result_burn',
  [
    new BQFunctionArgument('choice', STRING),
    new BQFunctionArgument('result', json),
  ],
  BIGNUMERIC,
  `
    CASE choice
      WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
        -- AmuletRules_BuyMemberTrafficResult
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
        + PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 5], 'numeric'))) -- .summary.holdingFees
        + PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 7], 'numeric'))) -- .summary.senderChangeFee
      WHEN 'AmuletRules_Transfer' THEN -- Amulet Burnt in Amulet Transfers
        -- TransferResult
        \`da-cn-scratchnet.functions.transferresult_fees\`(result)
      WHEN 'AmuletRules_CreateTransferPreapproval' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
        + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))) -- .transferResult
      WHEN 'AmuletRules_CreateExternalPartySetupProposal' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
        + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([3], 'record'))) -- .transferResult
      WHEN 'TransferPreapproval_Renew' THEN
        PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
        + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))) -- .transferResult
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
                    SUM(\`da-cn-scratchnet.functions.result_burn\`(e.choice,
                                              e.result)) fees
                FROM
                    \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
                WHERE
                    ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                                  'AmuletRules_Transfer',
                                  'AmuletRules_CreateTransferPreapproval',
                                  'AmuletRules_CreateExternalPartySetupProposal')
                        AND e.template_id_entity_name = 'AmuletRules')
                        OR (e.choice = 'TransferPreapproval_Renew'
                            AND e.template_id_entity_name = 'TransferPreapproval'))
                  AND e.template_id_module_name = 'Splice.AmuletRules'
                  AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id_arg,
                          e.record_time, e.migration_id))
            UNION ALL (-- Purchasing ANS Entries
                SELECT
                    SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, \`da-cn-scratchnet.functions.daml_record_path\`([2, 0], 'numeric')))) fees -- .amount.initialAmount
                FROM
                    \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e,
                    \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_creates\` c
                WHERE
                    ((e.choice = 'SubscriptionInitialPayment_Collect'
                        AND e.template_id_entity_name = 'SubscriptionInitialPayment'
                        AND c.contract_id = JSON_VALUE(e.result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'contractId'))) -- .amulet
                        OR (e.choice = 'SubscriptionPayment_Collect'
                            AND e.template_id_entity_name = 'SubscriptionPayment'
                            AND c.contract_id = JSON_VALUE(e.result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'contractId')))) -- .amulet
                  AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
                  AND c.template_id_module_name = 'Splice.Amulet'
                  AND c.template_id_entity_name = 'Amulet'
                  AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id_arg,
                        e.record_time, e.migration_id)
                  AND c.record_time != -62135596800000000)))
  `
)

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
      \`da-cn-scratchnet.functions.locked\`(as_of_record_time, migration_id) as locked,
      \`da-cn-scratchnet.functions.unlocked\`(as_of_record_time, migration_id) as unlocked,
      \`da-cn-scratchnet.functions.locked\`(as_of_record_time, migration_id) + \`da-cn-scratchnet.functions.unlocked\`(as_of_record_time, migration_id) as current_supply_total,
      \`da-cn-scratchnet.functions.unminted\`(as_of_record_time, migration_id) as unminted,
      \`da-cn-scratchnet.functions.minted\`(as_of_record_time, migration_id) as minted,
      \`da-cn-scratchnet.functions.minted\`(as_of_record_time, migration_id) + \`da-cn-scratchnet.functions.unminted\`(as_of_record_time, migration_id) as allowed_mint,
      \`da-cn-scratchnet.functions.burned\`(as_of_record_time, migration_id) as burned
  `
)

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
]
