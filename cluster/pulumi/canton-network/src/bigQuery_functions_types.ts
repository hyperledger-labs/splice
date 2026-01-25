// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

abstract class BQType {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  abstract toPulumi(): any;
  abstract toSql(): string;
}

class BQBasicType extends BQType {
  private readonly type: string;
  public constructor(type: string) {
    super();
    this.type = type;
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public toPulumi(): any {
    return { typeKind: this.type };
  }

  public toSql(): string {
    return this.type;
  }
}

export const STRING = new BQBasicType('STRING');
export const INT64 = new BQBasicType('INT64');
export const TIMESTAMP = new BQBasicType('TIMESTAMP');
export const BIGNUMERIC = new BQBasicType('BIGNUMERIC');
export const json = new BQBasicType('JSON'); // JSON is a reserved word in TypeScript
export const BOOL = new BQBasicType('BOOL');
export const FLOAT64 = new BQBasicType('FLOAT64');

export class BQArray extends BQType {
  private readonly elementType: BQType;
  public constructor(elementType: BQType) {
    super();
    this.elementType = elementType;
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public toPulumi(): any {
    return { typeKind: 'ARRAY', arrayElementType: this.elementType.toPulumi() };
  }

  public toSql(): string {
    return `ARRAY<${this.elementType.toSql()}>`;
  }
}

export class BQStruct extends BQType {
  private readonly structTypes: { name: string; type: BQType }[];
  public constructor(structTypes: { name: string; type: BQType }[]) {
    super();
    this.structTypes = structTypes;
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public toPulumi(): any {
    return {
      typeKind: 'STRUCT',
      structType: {
        fields: this.structTypes.map(st => ({
          name: st.name,
          type: st.type.toPulumi(),
        })),
      },
    };
  }

  public toSql(): string {
    return `STRUCT<${this.structTypes.map(st => `${st.name} ${st.type.toSql()}`).join(', ')}>`;
  }
}

export class BQFunctionArgument {
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
  public abstract toPulumi(
    project: string,
    installInDataset: gcp.bigquery.Dataset,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): pulumi.Resource;
  public abstract toSql(
    project: string,
    installInDataset: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string;

  protected replaceDatasets(
    project: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string {
    return this.definitionBody
      .replaceAll('$$FUNCTIONS_DATASET$$', `${project}.${functionsDataset}`)
      .replaceAll('$$SCAN_DATASET$$', `${project}.${scanDataset}`)
      .replaceAll('$$DASHBOARDS_DATASET$$', `${project}.${dashboardsDataset}`);
  }

  protected replaceDatasetsPulumi(
    project: string,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset
  ): pulumi.Output<string> {
    return pulumi
      .all([functionsDataset.datasetId, scanDataset.datasetId, dashboardsDataset.datasetId])
      .apply(([fd, sd, dd]) => this.replaceDatasets(project, fd, sd, dd));
  }
}

export class BQScalarFunction extends BQFunction {
  private readonly returnType: BQType;

  public constructor(
    name: string,
    args: BQFunctionArgument[],
    returnType: BQType,
    definitionBody: string
  ) {
    super(name, args, definitionBody);
    this.returnType = returnType;
  }

  public toPulumi(
    project: string,
    installInDataset: gcp.bigquery.Dataset,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(
      this.name,
      {
        datasetId: installInDataset.datasetId,
        routineId: this.name,
        routineType: 'SCALAR_FUNCTION',
        language: 'SQL',
        definitionBody: this.replaceDatasetsPulumi(
          project,
          functionsDataset,
          scanDataset,
          dashboardsDataset
        ),
        arguments: this.arguments.map(arg => arg.toPulumi()),
        returnType: JSON.stringify(this.returnType.toPulumi()),
      },
      { dependsOn }
    );
  }

  public toSql(
    project: string,
    installInDataset: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string {
    const body = this.replaceDatasets(project, functionsDataset, scanDataset, dashboardsDataset);

    return `
      CREATE OR REPLACE FUNCTION ${installInDataset}.${this.name}(
        ${this.arguments.map(arg => arg.toSql()).join(',\n        ')}
      )
      RETURNS ${this.returnType.toSql()}
      AS (
      ${body}
      );
    `;
  }
}

export class BQColumn {
  private readonly name: string;
  private readonly type: BQType;

  public constructor(name: string, type: BQType) {
    this.name = name;
    this.type = type;
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  public toPulumi(simpleSqlTypes: boolean = false): any {
    return {
      name: this.name,
      type: simpleSqlTypes ? this.type.toSql() : this.type.toPulumi(),
    };
  }

  public toSql(): string {
    return `${this.name} ${this.type.toSql()}`;
  }
}

export class BQTableFunction extends BQFunction {
  private readonly returnTableType: BQColumn[];

  public constructor(
    name: string,
    args: BQFunctionArgument[],
    returnTableType: BQColumn[],
    definitionBody: string
  ) {
    super(name, args, definitionBody);
    this.returnTableType = returnTableType;
  }

  public toPulumi(
    project: string,
    installInDataset: gcp.bigquery.Dataset,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(
      this.name,
      {
        datasetId: installInDataset.datasetId,
        routineId: this.name,
        routineType: 'TABLE_VALUED_FUNCTION',
        language: 'SQL',
        definitionBody: this.replaceDatasetsPulumi(
          project,
          functionsDataset,
          scanDataset,
          dashboardsDataset
        ),
        arguments: this.arguments.map(arg => arg.toPulumi()),
        returnTableType: JSON.stringify({
          columns: this.returnTableType.map(col => col.toPulumi()),
        }),
      },
      { dependsOn }
    );
  }

  public toSql(
    project: string,
    installInDataset: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string {
    const body = this.replaceDatasets(project, functionsDataset, scanDataset, dashboardsDataset);

    return `
      CREATE OR REPLACE TABLE FUNCTION ${installInDataset}.${this.name}(
        ${this.arguments.map(arg => arg.toSql()).join(',\n        ')}
      )
      RETURNS TABLE<
        ${this.returnTableType.map(col => col.toSql()).join(',\n        ')}
      >
      AS (
      ${body}
      );
    `;
  }
}

// While logical views are created as a Table in pulumi, we model them as a function, so that we can treat their query similarly to the function bodies.
export class BQLogicalView extends BQFunction {
  public constructor(name: string, definitionBody: string) {
    super(name, [], definitionBody);
  }

  public toPulumi(
    project: string,
    installInDataset: gcp.bigquery.Dataset,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Table {
    return new gcp.bigquery.Table(
      this.name,
      {
        datasetId: installInDataset.datasetId,
        tableId: this.name,
        deletionProtection: false, // no point in deletion protection for a view, it doesn't hold data
        view: {
          query: this.replaceDatasetsPulumi(
            project,
            functionsDataset,
            scanDataset,
            dashboardsDataset
          ),
          useLegacySql: false,
        },
      },
      { dependsOn }
    );
  }

  public toSql(
    project: string,
    installInDataset: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string {
    const body = this.replaceDatasets(project, functionsDataset, scanDataset, dashboardsDataset);

    return `
      CREATE OR REPLACE VIEW ${installInDataset}.${this.name}
      AS (
      ${body}
      );
    `;
  }
}

export class BQProcedure extends BQFunction {
  public constructor(name: string, args: BQFunctionArgument[], definitionBody: string) {
    super(name, args, definitionBody);
  }

  public toPulumi(
    project: string,
    installInDataset: gcp.bigquery.Dataset,
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dashboardsDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(
      this.name,
      {
        datasetId: installInDataset.datasetId,
        routineId: this.name,
        routineType: 'PROCEDURE',
        language: 'SQL',
        definitionBody: this.replaceDatasetsPulumi(
          project,
          functionsDataset,
          scanDataset,
          dashboardsDataset
        ),
        arguments: this.arguments.map(arg => arg.toPulumi()),
      },
      { dependsOn }
    );
  }

  public toSql(
    project: string,
    installInDataset: string,
    functionsDataset: string,
    scanDataset: string,
    dashboardsDataset: string
  ): string {
    const body = this.replaceDatasets(project, functionsDataset, scanDataset, dashboardsDataset);

    return `
      CREATE OR REPLACE PROCEDURE ${installInDataset}.${this.name}(
        ${this.arguments.map(arg => arg.toSql()).join(',\n        ')}
      )
      BEGIN
      ${body}
      END;
    `;
  }
}

export class BQTable {
  protected readonly name: string;
  protected readonly columns: BQColumn[];

  public constructor(name: string, columns: BQColumn[]) {
    this.name = name;
    this.columns = columns;
  }

  public toPulumi(
    installInDataset: gcp.bigquery.Dataset,
    deletionProtection: boolean
  ): gcp.bigquery.Table {
    return new gcp.bigquery.Table(this.name, {
      datasetId: installInDataset.datasetId,
      tableId: this.name,
      deletionProtection,
      schema: JSON.stringify(this.columns.map(col => col.toPulumi(true))),
    });
  }

  public toSql(dataset: string): string {
    return `
      CREATE OR REPLACE TABLE \`${dataset}.${this.name}\` (
        ${this.columns.map(col => col.toSql()).join(',\n        ')}
      );
    `;
  }
}
