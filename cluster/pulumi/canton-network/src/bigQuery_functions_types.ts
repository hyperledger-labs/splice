// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
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
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine;
  public abstract toSql(project: string, functionsDataset: string, scanDataset: string): string;
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
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(
      this.name,
      {
        datasetId: functionsDataset.datasetId,
        routineId: this.name,
        routineType: 'SCALAR_FUNCTION',
        language: 'SQL',
        definitionBody: functionsDataset.datasetId.apply(fd =>
          scanDataset.datasetId.apply(sd =>
            this.definitionBody
              .replaceAll('$$FUNCTIONS_DATASET$$', `${project}.${fd}`)
              .replaceAll('$$SCAN_DATASET$$', `${project}.${sd}`)
          )
        ),
        arguments: this.arguments.map(arg => arg.toPulumi()),
        returnType: JSON.stringify(this.returnType.toPulumi()),
      },
      { dependsOn }
    );
  }

  public toSql(project: string, functionsDataset: string, scanDataset: string): string {
    const body = this.definitionBody
      .replaceAll('$$FUNCTIONS_DATASET$$', `${project}.${functionsDataset}`)
      .replaceAll('$$SCAN_DATASET$$', `${project}.${scanDataset}`);

    return `
      CREATE OR REPLACE FUNCTION ${functionsDataset}.${this.name}(
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
    functionsDataset: gcp.bigquery.Dataset,
    scanDataset: gcp.bigquery.Dataset,
    dependsOn?: pulumi.Resource[]
  ): gcp.bigquery.Routine {
    return new gcp.bigquery.Routine(
      this.name,
      {
        datasetId: functionsDataset.datasetId,
        routineId: this.name,
        routineType: 'TABLE_VALUED_FUNCTION',
        language: 'SQL',
        definitionBody: functionsDataset.datasetId.apply(fd =>
          scanDataset.datasetId.apply(sd =>
            this.definitionBody
              .replaceAll('$$FUNCTIONS_DATASET$$', `${project}.${fd}`)
              .replaceAll('$$SCAN_DATASET$$', `${project}.${sd}`)
          )
        ),
        arguments: this.arguments.map(arg => arg.toPulumi()),
        returnTableType: JSON.stringify({
          columns: this.returnTableType.map(col => col.toPulumi()),
        }),
      },
      { dependsOn }
    );
  }

  public toSql(project: string, functionsDataset: string, scanDataset: string): string {
    const body = this.definitionBody
      .replaceAll('$$FUNCTIONS_DATASET$$', `${project}.${functionsDataset}`)
      .replaceAll('$$SCAN_DATASET$$', `${project}.${scanDataset}`);

    return `
      CREATE OR REPLACE TABLE FUNCTION ${functionsDataset}.${this.name}(
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
