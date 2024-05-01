import * as k8s from '@pulumi/kubernetes';
import { config } from 'cn-pulumi-common';

import { namespace } from './namespace';
import { Version } from './version';

export const operator = new k8s.helm.v3.Release('pulumi-kubernetes-operator', {
  name: 'pulumi-kubernetes-operator',
  chart: 'oci://ghcr.io/pulumi/helm-charts/pulumi-kubernetes-operator',
  version: '0.7.3',
  namespace: namespace.ns.metadata.name,
  values: {
    deploymentStrategy: 'Recreate',
    image: {
      registry: 'us-central1-docker.pkg.dev',
      repository: 'da-cn-shared/cn-images/pulumi-kubernetes-operator',
      tag: Version,
      pullPolicy: 'Always',
    },
    controller: {
      args: ['--zap-level=debug', '--zap-time-encoding=iso8601', '--zap-encoder=json'],
    },
    createClusterRole: true,
    serviceMonitor: {
      enabled: true,
      namespace: namespace.logicalName,
      service: {
        annotations: {},
        type: 'ClusterIP',
      },
    },
    extraEnv: [
      {
        name: 'CLOUDSDK_CORE_PROJECT',
        value: config.requireEnv('CLOUDSDK_CORE_PROJECT'),
      },
      {
        name: 'CLOUDSDK_COMPUTE_REGION',
        value: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
      },
      {
        name: 'CLOUDSDK_COMPUTE_ZONE',
        value: config.requireEnv('CLOUDSDK_COMPUTE_ZONE'),
      },
      {
        name: 'GOOGLE_CREDENTIALS',
        value: config.requireEnv('GOOGLE_CREDENTIALS'),
      },
      {
        name: 'CN_PULUMI_LOAD_ENV_CONFIG_FILE',
        value: 'true',
      },
    ],
  },
});
