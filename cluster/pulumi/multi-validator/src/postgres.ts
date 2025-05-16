import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  activeVersion,
  CnInput,
  ExactNamespace,
  installSpliceRunbookHelmChart,
  installPostgresPasswordSecret,
  InstalledHelmChart,
} from 'splice-pulumi-common';

import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  return installSpliceRunbookHelmChart(
    xns,
    name,
    'splice-postgres',
    {
      persistence: { secretName },
      db: { volumeSize: config.postgresPvcSize, maxConnections: 1000 },
      resources: {
        requests: { memory: '10Gi' },
        limits: { memory: '20Gi' },
      },
    },
    activeVersion,
    { dependsOn: [passwordSecret, ...dependsOn] }
  );
}
