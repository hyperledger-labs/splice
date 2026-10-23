// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CLUSTER_HOSTNAME,
  config,
  ExactNamespace,
  exactNamespace,
  imagePullSecret,
  installSpliceHelmChart,
  type Auth0Client,
} from '@canton-network/splice-pulumi-common';
import {
  dSyncMigrationConfigForNode,
  installLoopback,
} from '@canton-network/splice-pulumi-common-sv';

function installIngress(xns: ExactNamespace) {
  const dsyncMigrationConfig = dSyncMigrationConfigForNode(xns.logicalName);

  return installSpliceHelmChart(
    xns,
    'ingress-sv',
    'splice-cluster-ingress-runbook',
    {
      withSvIngress: true, // this actually installs too much, but doesn't seem worth having a separate config parameter to avoid that
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: dsyncMigrationConfig.runningMigrations().map(x => x.id.toString()),
        },
      },
      spliceDomainNames: {},
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: xns.logicalName,
        svIngressName: xns.logicalName,
      },
      rateLimit: {
        scan: {
          enable: false,
        },
      },
    },
    dsyncMigrationConfig.active.version,
    { dependsOn: [xns.ns] }
  );
}

export async function installNode(auth0Client: Auth0Client, namespace: string): Promise<void> {
  const xns = exactNamespace(namespace, false);
  imagePullSecret(xns);
  installLoopback(xns);
  installIngress(xns);
}
