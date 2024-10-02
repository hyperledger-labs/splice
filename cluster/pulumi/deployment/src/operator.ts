import * as k8s from '@pulumi/kubernetes';
import { config, infraAffinityAndTolerations } from 'splice-pulumi-common';

import { namespace } from './namespace';
import { Version } from './version';

const credentialsSecret = new k8s.core.v1.Secret('gke-credentials', {
  metadata: {
    name: 'gke-credentials',
    namespace: namespace.ns.metadata.name,
  },
  type: 'Opaque',
  stringData: {
    googleCredentials: config.requireEnv('GOOGLE_CREDENTIALS'),
  },
});

export const operator = new k8s.helm.v3.Release('pulumi-kubernetes-operator', {
  name: 'pulumi-kubernetes-operator',
  chart: 'oci://ghcr.io/pulumi/helm-charts/pulumi-kubernetes-operator',
  version: '0.7.3',
  namespace: namespace.ns.metadata.name,
  values: {
    resources: {
      limits: {
        cpu: 5,
        memory: '10G',
      },
      requests: {
        cpu: 1,
        memory: '2G',
      },
    },
    terminationGracePeriodSeconds: 600,
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
        name: 'GOOGLE_APPLICATION_CREDENTIALS',
        value: '/app/gcp-credentials.json',
      },
      {
        name: 'GOOGLE_CREDENTIALS',
        valueFrom: {
          secretKeyRef: {
            name: credentialsSecret.metadata.name,
            key: 'googleCredentials',
          },
        },
      },
      {
        name: 'CN_PULUMI_LOAD_ENV_CONFIG_FILE',
        value: 'true',
      },
      {
        name: 'SPLICE_OPERATOR_DEPLOYMENT',
        value: 'true',
      },
    ],
    extraVolumeMounts: [
      {
        name: 'gcp-credentials',
        mountPath: '/app/gcp-credentials.json',
        subPath: 'googleCredentials',
      },
    ],
    extraVolumes: [
      {
        name: 'gcp-credentials',
        secret: {
          secretName: credentialsSecret.metadata.name,
        },
      },
    ],
    ...infraAffinityAndTolerations,
  },
});
