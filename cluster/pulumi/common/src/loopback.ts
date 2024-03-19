import * as k8s from '@pulumi/kubernetes';

import { CnChartVersion, installCNRunbookHelmChart } from './helm';
import { ExactNamespace } from './utils';

export function installLoopback(
  namespace: ExactNamespace,
  clusterBasename: string,
  version: CnChartVersion
): k8s.helm.v3.Release {
  return installCNRunbookHelmChart(
    namespace,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: clusterBasename,
      },
    },
    version,
    [namespace.ns]
  );
}
