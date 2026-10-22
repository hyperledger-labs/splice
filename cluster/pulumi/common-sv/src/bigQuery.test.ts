// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as os from 'os';
import { collectResources } from '@canton-network/splice-pulumi-common/src/test';
import { beforeAll, describe, expect, test } from '@jest/globals';

import type { FlywayMigrationToWaitFor } from './bigQueryFlywayMigration';
import type { ScanBigQueryConfig } from './singleSvConfig';

/*eslint no-process-env: "off"*/
// use the mock cluster's environment, as `make cluster/pulumi/test` does for dump-config
process.env.CN_PULUMI_LOAD_ENV_CONFIG_FILE = 'true';
process.env.GCP_CLUSTER_BASENAME = 'mock';
// Force this to the splice deployment dir as mock is always in there.
process.env.DEPLOYMENT_DIR = `${process.env.SPLICE_ROOT}/cluster/deployment`;

const legacyDatasetId = 'mock_scan';

async function installBigQuery(
  enableLegacyDatastream: boolean,
  enableStagProdDatastream: boolean,
  flywayMigrationToWaitFor: FlywayMigrationToWaitFor
): Promise<pulumi.Resource[]> {
  // imported here so the environment above is in place when the config modules load
  const { configureScanBigQuery } = await import('./bigQuery');
  const [, resources] = await collectResources(() =>
    configureScanBigQuery({
      namespace: { ns: new k8s.core.v1.Namespace('sv-1', {}), logicalName: 'sv-1' },
      bigQueryConfig: {
        dataset: legacyDatasetId,
        prefix: 'mock',
        enableLegacyDatastream,
        enableStagProdDatastream,
        legacyDesiredState: 'RUNNING',
        stagProdDesiredState: 'RUNNING',
        retentionPeriodSeconds: 7 * 24 * 3600,
        flywayMigrationToWaitFor,
      } satisfies ScanBigQueryConfig,
      scanReference: { type: 'external', databaseInstanceNamePrefix: 'sv-1-cn-apps-pg' },
    })
  );
  return resources;
}

function outputValue<T>(output: pulumi.Output<T>): Promise<T> {
  return new Promise(resolve => output.apply(resolve));
}

async function legacyPath(resources: pulumi.Resource[]) {
  const ids = <R extends pulumi.Resource>(
    isInstance: (r: unknown) => r is R,
    id: (r: R) => pulumi.Output<string | undefined>
  ) => Promise.all(resources.filter(isInstance).map(r => outputValue(id(r))));
  const datasetIds = await ids(gcp.bigquery.Dataset.isInstance, r => r.datasetId);
  const streamIds = await ids(gcp.datastream.Stream.isInstance, r => r.streamId);
  const profileIds = await ids(
    gcp.datastream.ConnectionProfile.isInstance,
    r => r.connectionProfileId
  );
  const commandCreates = await ids(command.local.Command.isInstance, r => r.create);
  return {
    dataset: datasetIds.includes(legacyDatasetId),
    stream: streamIds.includes('sv-1-scan-update-history'),
    destinationProfile: profileIds.includes('sv-1-scan-bq-cxn'),
    publicationAndSlot: commandCreates.some(create =>
      create?.includes('--publication-name="update_history_datastream_pub"')
    ),
  };
}

describe('configureScanBigQuery', () => {
  beforeAll(async () => {
    await pulumi.runtime.setMocks({
      newResource: args => ({ id: `${args.name}_id`, state: args.inputs }),
      call: args => {
        switch (args.token) {
          case 'gcp:organizations/getProject:getProject':
            return { projectId: process.env.CLOUDSDK_CORE_PROJECT };
          // the scan DB instance is looked up by name prefix and cluster label
          case 'gcp:sql/getDatabaseInstances:getDatabaseInstances':
            return {
              instances: [
                {
                  name: 'sv-1-cn-apps-pg-1234',
                  settings: [{ userLabels: { cluster: process.env.GCP_CLUSTER_BASENAME } }],
                },
              ],
            };
          default:
            return args.inputs;
        }
      },
    });
  });

  test('disabling the legacy Datastream removes its stream path but keeps its dataset', async () => {
    const resources = await installBigQuery(false, true, 'V068__app_activity_record_meta.sql');
    expect(await legacyPath(resources)).toEqual({
      dataset: true,
      stream: false,
      destinationProfile: false,
      publicationAndSlot: false,
    });
  });

  test('enabling the legacy Datastream installs its whole path', async () => {
    const resources = await installBigQuery(true, false, 'V068__app_activity_record_meta.sql');
    expect(await legacyPath(resources)).toEqual({
      dataset: true,
      stream: true,
      destinationProfile: true,
      publicationAndSlot: true,
    });
  });

  test.each(['V068__app_activity_record_meta.sql', 'V078__partition_update_history.sql'] as const)(
    'both publication and slot commands wait for %s',
    async migration => {
      const resources = await installBigQuery(true, true, migration);
      const commands = resources.filter(command.local.Command.isInstance);
      const scripts = await Promise.all(
        commands.flatMap(c => [outputValue(c.create), outputValue(c.delete)])
      );
      expect(scripts).toHaveLength(4);
      for (const script of scripts) {
        expect(script).toContain(`--flyway-migration-to-wait-for="${migration}"`);
      }
    }
  );
});

describe('flywayMigrationToWaitFor', () => {
  test('waits for V068 by default and allows V078', async () => {
    const { FlywayMigrationToWaitForSchema } = await import('./bigQueryFlywayMigration');
    expect(FlywayMigrationToWaitForSchema.parse(undefined)).toBe(
      'V068__app_activity_record_meta.sql'
    );
    expect(FlywayMigrationToWaitForSchema.parse('V078__partition_update_history.sql')).toBe(
      'V078__partition_update_history.sql'
    );
  });

  test.each(['V077__verdict_add_round_number.sql', 'V078__partition_update_history'])(
    'rejects %s',
    async migration => {
      const { FlywayMigrationToWaitForSchema } = await import('./bigQueryFlywayMigration');
      expect(FlywayMigrationToWaitForSchema.safeParse(migration).success).toBe(false);
    }
  );

  test('checkFlywayMigrationsExist names the missing migrations', async () => {
    const { checkFlywayMigrationsExist } = await import('./bigQueryFlywayMigration');
    const dir = fs.mkdtempSync(`${os.tmpdir()}/flyway-`);
    try {
      fs.writeFileSync(`${dir}/V068__app_activity_record_meta.sql`, '');
      expect(() =>
        checkFlywayMigrationsExist(dir, [
          'V068__app_activity_record_meta.sql',
          'V078__partition_update_history.sql',
        ])
      ).toThrow(/: V078__partition_update_history\.sql$/);
    } finally {
      fs.rmSync(dir, { recursive: true });
    }
  });
});
