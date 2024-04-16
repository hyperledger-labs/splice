import * as k8s from '@pulumi/kubernetes';
import * as random from '@pulumi/random';
import {
  defaultVersion,
  ExactNamespace,
  installCNRunbookHelmChart,
  installPostgresPasswordSecret,
} from 'cn-pulumi-common';

export function installPostgres(xns: ExactNamespace, name: string): k8s.helm.v3.Release {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  return installCNRunbookHelmChart(
    xns,
    name,
    'cn-postgres',
    {
      persistence: { secretName },
      db: { volumeSize: '400Gi', maxConnections: 1000 },
    },
    defaultVersion,
    [passwordSecret]
  );
}
