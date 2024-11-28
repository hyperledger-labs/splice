import { CnChartVersion } from './artifacts';
import { InstalledHelmChart, installSpliceRunbookHelmChart } from './helm';
import { ExactNamespace } from './utils';

export function installLoopback(
  namespace: ExactNamespace,
  clusterHostname: string,
  version: CnChartVersion
): InstalledHelmChart {
  return installSpliceRunbookHelmChart(
    namespace,
    'loopback',
    'splice-cluster-loopback-gateway',
    {
      cluster: {
        hostname: clusterHostname,
      },
    },
    version,
    { dependsOn: [namespace.ns] }
  );
}
