// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { config, GCP_PROJECT } from '@lfdecentralizedtrust/splice-pulumi-common';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';
import { gkeClusterConfig, GkeNodePoolConfig } from './config';

export function installNodePools(): void {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const cluster = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE')
    ? `projects/${GCP_PROJECT}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_ZONE')}/clusters/${clusterName}`
    : clusterName;

  const nodepoolLocation = config.optionalEnv('CLOUDSDK_HYPERDISK_NODEPOOL_COMPUTE_ZONE');

  if (gkeClusterConfig.nodePools.hyperdiskApps) {
    hyperdiskNodePool(cluster, gkeClusterConfig.nodePools.hyperdiskApps, nodepoolLocation);
  }
  const appsNodePoolConfig = gkeClusterConfig.nodePools.apps;

  if (
    hyperdiskSupportConfig.hyperdiskSupport.enabled &&
    !hyperdiskSupportConfig.hyperdiskSupport.migrating
  ) {
    hyperdiskNodePool(cluster, appsNodePoolConfig, nodepoolLocation);
  } else {
    appsNodePool(cluster, appsNodePoolConfig);
  }

  new gcp.container.NodePool('cn-infra-node-pool', {
    cluster,
    nodeConfig: {
      machineType: gkeClusterConfig.nodePools.infra.nodeType,
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
      loggingVariant: 'DEFAULT',
    },
    location: config.optionalEnv('CLOUDSDK_NODEPOOL_COMPUTE_ZONE'),
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
      loggingVariant: 'DEFAULT',
    },
    location: config.optionalEnv('CLOUDSDK_NODEPOOL_COMPUTE_ZONE'),
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: 1,
      maxNodeCount: 3,
    },
  });
}
function hyperdiskNodePool(cluster: string, config: GkeNodePoolConfig, location?: string) {
  new gcp.container.NodePool('cn-apps-node-pool-hd', {
    cluster,
    nodeConfig: {
      machineType: config.nodeType,
      bootDisk: {
        diskType: 'hyperdisk-balanced',
        sizeGb: 100,
      },
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_apps',
          value: 'true',
        },
      ],
      labels: {
        cn_apps: 'hyperdisk',
      },
      loggingVariant: 'DEFAULT',
    },
    nodeLocations: location ? [location] : undefined,
    initialNodeCount: 0,
    autoscaling: {
      minNodeCount: config.minNodes,
      maxNodeCount: config.maxNodes,
    },
  });
}
function appsNodePool(cluster: string, appsNodePoolConfig: GkeNodePoolConfig) {
  new gcp.container.NodePool('cn-apps-node-pool', {
    cluster,
    nodeConfig: {
      machineType: appsNodePoolConfig.nodeType,
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_apps',
          value: 'true',
        },
      ],
      labels: {
        cn_apps: 'standard',
      },
      loggingVariant: 'DEFAULT',
    },
    initialNodeCount: 0,
    autoscaling: {
      minNodeCount: appsNodePoolConfig.minNodes,
      maxNodeCount: appsNodePoolConfig.maxNodes,
    },
  });
}
