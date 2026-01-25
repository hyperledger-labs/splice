// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { CustomResource } from '@pulumi/kubernetes/apiextensions';
import { Input, Inputs } from '@pulumi/pulumi';

import { ObservabilityReleaseName } from './utils';

export class PodMonitor extends CustomResource {
  constructor(
    name: string,
    namespace: Input<string>,
    spec: {
      matchLabels: Inputs;
      podMetricsEndpoints: Array<{
        port: string;
        path: string;
        relabelings?: Array<unknown>;
        metricRelabelings?: Array<unknown>;
      }>;
      namespaces?: Array<Input<string>>;
    },
    opts?: pulumi.CustomResourceOptions
  ) {
    super(
      name,
      {
        apiVersion: 'monitoring.coreos.com/v1',
        kind: 'PodMonitor',
        metadata: {
          name: name,
          namespace: namespace,
          labels: {
            monitoring: 'istio-proxies',
            release: ObservabilityReleaseName,
          },
        },
        spec: {
          jobLabel: 'app',
          selector: {
            matchLabels: spec.matchLabels,
          },
          namespaceSelector: spec.namespaces
            ? {
                matchNames: spec.namespaces,
              }
            : {
                any: true,
              },
          podMetricsEndpoints: spec.podMetricsEndpoints.map(endpoint => {
            return {
              honorLabels: true,
              ...endpoint,
            };
          }),
        },
      },
      opts
    );
  }
}

export class ServiceMonitor extends CustomResource {
  constructor(
    name: string,
    matchLabels: Inputs,
    port: string,
    namespace: Input<string>,
    opts?: pulumi.CustomResourceOptions
  ) {
    super(
      name,
      {
        apiVersion: 'monitoring.coreos.com/v1',
        kind: 'ServiceMonitor',
        metadata: {
          name: name,
          namespace: namespace,
          labels: {
            release: ObservabilityReleaseName,
          },
        },
        spec: {
          selector: {
            matchLabels: matchLabels,
          },
          namespaceSelector: {
            any: true,
          },
          endpoints: [
            {
              port: port,
              honorLabels: true,
            },
          ],
        },
      },
      opts
    );
  }
}
