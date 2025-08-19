// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { GCP_PROJECT, config } from '@lfdecentralizedtrust/splice-pulumi-common';

export function installNodePools(): void {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const cluster = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE')
    ? `projects/${GCP_PROJECT}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_ZONE')}/clusters/${clusterName}`
    : clusterName;

  new gcp.container.NodePool('cn-apps-node-pool', {
    name: 'cn-apps-pool',
    cluster,
    nodeConfig: {
      machineType: config.requireEnv('GCP_CLUSTER_NODE_TYPE'),
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
      loggingVariant: config.requireEnv('GCP_CLUSTER_LOGGING_VARIANT'),
    },
    initialNodeCount: 0,
    autoscaling: {
      minNodeCount: parseInt(config.requireEnv('GCP_CLUSTER_MIN_NODES')),
      maxNodeCount: parseInt(config.requireEnv('GCP_CLUSTER_MAX_NODES')),
    },
  });

  new gcp.container.NodePool('cn-infra-node-pool', {
    name: 'cn-infra-pool',
    cluster,
    nodeConfig: {
      machineType: config.optionalEnv('INFRA_NODE_POOL_MACHINE_TYPE') || 'e2-standard-8',
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
      loggingVariant: config.requireEnv('GCP_CLUSTER_LOGGING_VARIANT'),
    },
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: 1,
      maxNodeCount: 3,
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
      loggingVariant: config.requireEnv('GCP_CLUSTER_LOGGING_VARIANT'),
    },
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: 1,
      maxNodeCount: 3,
    },
  });
}
