// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CnChartVersion } from './artifacts';
import { DecentralizedSynchronizerUpgradeConfig } from './domainMigration';
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
      cometbftPorts: {
        // This ensures the loopback exposes the right ports. We need a +1 since the helm chart does an exclusive range
        domains: DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1,
      },
    },
    version,
    { dependsOn: [namespace.ns] }
  );
}
