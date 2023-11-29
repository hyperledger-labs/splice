import * as pulumi from '@pulumi/pulumi';
import { CustomResource } from '@pulumi/kubernetes/apiextensions';
import { Input, Inputs } from '@pulumi/pulumi';

export class PodMonitor extends CustomResource {
  constructor(
    name: string,
    matchLabels: Inputs,
    podMetricsEndpoints: Array<{ port: string; path: string }>,
    namespace: Input<string>,
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
        },
        spec: {
          selector: {
            matchLabels: matchLabels,
          },
          namespaceSelector: {
            any: true,
          },
          podMetricsEndpoints: podMetricsEndpoints,
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
            },
          ],
        },
      },
      opts
    );
  }
}
