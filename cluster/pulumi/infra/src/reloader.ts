// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  HELM_MAX_HISTORY_SIZE,
  exactNamespace,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export function configureReloader(): k8s.helm.v3.Release {
  const ns = exactNamespace('reloader', false, true);

  return new k8s.helm.v3.Release(
    'reloader',
    {
      name: 'reloader',
      chart: 'reloader',
      version: '2.2.8',
      namespace: ns.ns.metadata.name,
      repositoryOpts: {
        repo: 'https://stakater.github.io/stakater-charts',
      },
      values: {
        image:{
          pullPolicy: "Always",
        },
        reloader: {
          logFormat: 'json',
          enableHA: true,
          readOnlyRootFileSystem: true,
          enableMetricsByNamespace: true,
          deployment: {
            ...infraAffinityAndTolerations,
          },
          containerSecurityContext: {
            capabilities: {
              drop: ['ALL']
            },
            allowPrivilegeEscalation: false,
            readOnlyRootFilesystem: true,
          }
        },
        serviceMonitor: {
          enabled: true,
        }
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [ns.ns],
    }
  );
}
