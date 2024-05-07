import * as k8s from '@pulumi/kubernetes';

import { CnChartVersion } from './artifacts';
import { installCNRunbookHelmChart } from './helm';
import { ExactNamespace } from './utils';

export function installLoopback(
  namespace: ExactNamespace,
  clusterHostname: string,
  version: CnChartVersion
): k8s.helm.v3.Release {
  return installCNRunbookHelmChart(
    namespace,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        hostname: clusterHostname,
      },
    },
    version,
    [namespace.ns]
  );
}
