// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  config,
  HELM_MAX_HISTORY_SIZE,
  imagePullSecret,
  infraAffinityAndTolerations,
} from 'splice-pulumi-common';

import { namespace } from './namespace';

export const imagePullDeps = imagePullSecret(namespace);

const secretName = (
  (imagePullDeps as pulumi.Resource[])
    .filter(e => e instanceof k8s.core.v1.Secret)
    .pop() as k8s.core.v1.Secret
).metadata.name;

export const operator = new k8s.helm.v3.Release(
  'pulumi-kubernetes-operator',
  {
    name: 'pulumi-kubernetes-operator',
    chart: 'oci://ghcr.io/pulumi/helm-charts/pulumi-kubernetes-operator',
    version: '2.1.0',
    namespace: namespace.ns.metadata.name,
    values: {
      limits: {
        cpu: 1,
        memory: config.optionalEnv('OPERATOR_MEMORY_LIMIT') || '2G',
      },
      resources: {
        requests: {
          cpu: 0.2,
          memory: config.optionalEnv('OPERATOR_MEMORY_REQUESTS') || '1G',
        },
      },
      imagePullSecrets: [{ name: secretName }],
      terminationGracePeriodSeconds: 1800,
      image: {
        pullPolicy: 'Always',
      },
      controller: {
        logLevel: 'debug',
        logFormat: 'json',
      },
      serviceMonitor: {
        enabled: true,
      },
      ...infraAffinityAndTolerations,
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
  },
  { dependsOn: imagePullDeps }
);
