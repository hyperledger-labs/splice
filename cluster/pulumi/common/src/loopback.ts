import * as k8s from '@pulumi/kubernetes';

import { installCNRunbookHelmChart } from './helm';
import { ExactNamespace } from './utils';

export function installLoopback(
  namespace: ExactNamespace,
  clusterBasename: string,
  localCharts: boolean,
  version: string | undefined
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
    localCharts,
    version,
    [namespace.ns]
  );
}
