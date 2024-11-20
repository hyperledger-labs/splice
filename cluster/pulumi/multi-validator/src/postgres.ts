import * as k8s from '@pulumi/kubernetes';
import * as random from '@pulumi/random';
import {
  activeVersion,
  ExactNamespace,
  installSpliceRunbookHelmChart,
  installPostgresPasswordSecret,
} from 'splice-pulumi-common';

export function installPostgres(xns: ExactNamespace, name: string): k8s.helm.v3.Release {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  return installSpliceRunbookHelmChart(
    xns,
    name,
    'splice-postgres',
    {
      persistence: { secretName },
      db: { volumeSize: '600Gi', maxConnections: 1000 },
      resources: {
        requests: { memory: '10Gi' },
        limits: { memory: '20Gi' },
      },
    },
    activeVersion,
    { dependsOn: [passwordSecret] }
  );
}
