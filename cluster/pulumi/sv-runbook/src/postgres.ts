import * as k8s from '@pulumi/kubernetes';
import * as random from '@pulumi/random';
import {
  ChartValues,
  ExactNamespace,
  installCNRunbookHelmChart,
  installPostgresPasswordSecret,
} from 'cn-pulumi-common';

import { localCharts, version } from './utils';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  values: ChartValues
): k8s.helm.v3.Release {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  return installCNRunbookHelmChart(xns, name, 'cn-postgres', values, localCharts, version, [
    passwordSecret,
  ]);
}
