// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { GCP_PROJECT, config } from '@lfdecentralizedtrust/splice-pulumi-common';

import { gkeClusterConfig } from './config';

export function installNodePools(): void {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const cluster = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE')
    ? `projects/${GCP_PROJECT}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_ZONE')}/clusters/${clusterName}`
    : clusterName;

  new gcp.container.NodePool('cn-apps-node-pool', {
    cluster,
    nodeConfig: {
      machineType: gkeClusterConfig.nodePools.apps.nodeType,
      bootDisk: gkeClusterConfig.nodePools.apps.bootDisk,
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_apps',
          value: 'true',
        },
      ],
      labels: {
        cn_apps: 'true',
      },
    },
    initialNodeCount: 0,
    autoscaling: {
      minNodeCount: gkeClusterConfig.nodePools.apps.minNodes,
      maxNodeCount: gkeClusterConfig.nodePools.apps.maxNodes,
    },
  });

  new gcp.container.NodePool('cn-infra-node-pool', {
    cluster,
    nodeConfig: {
      machineType: gkeClusterConfig.nodePools.infra.nodeType,
      bootDisk: gkeClusterConfig.nodePools.infra.bootDisk,
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_infra',
          value: 'true',
        },
      ],
      labels: {
        cn_infra: 'true',
      },
    },
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: gkeClusterConfig.nodePools.infra.minNodes,
      maxNodeCount: gkeClusterConfig.nodePools.infra.maxNodes,
    },
  });

  new gcp.container.NodePool('gke-node-pool', {
    name: 'gke-pool',
    cluster,
    nodeConfig: {
      machineType: 'e2-standard-4',
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'components.gke.io/gke-managed-components',
          value: 'true',
        },
      ],
    },
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: 1,
      maxNodeCount: 3,
    },
  });
}
