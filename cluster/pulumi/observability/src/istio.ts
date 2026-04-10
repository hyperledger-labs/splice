// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';
import { PodMonitor, ServiceMonitor } from '@lfdecentralizedtrust/splice-pulumi-common/src/metrics';

export function istioMonitoring(
  ingressNs: ExactNamespace,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  const svc = new ServiceMonitor(
    'istiod-service-monitor',
    { istio: 'pilot' },
    'http-monitoring',
    ingressNs.ns.metadata.name,
    { dependsOn }
  );
  const sidecar = new PodMonitor(
    `istio-sidecar-monitor`,
    ingressNs.ns.metadata.name,
    {
      matchLabels: {
        'security.istio.io/tlsMode': 'istio',
      },
      // specify the namespaces to monitor, to scrape only the istio-proxy sidecars used for our apps
      namespaces: Array.from({ length: 16 }, (_, i) => `sv-${i + 1}`).concat([
        'sv',
        'splitwell',
        'validator1',
        'validator',
      ]),
      //https://github.com/istio/istio/blob/master/samples/addons/extras/prometheus-operator.yaml#L16
      podMetricsEndpoints: [
        {
          port: 'http-envoy-prom',
          path: '/stats/prometheus',
          // keep only istio metrics, drop envoy metrics
          metricRelabelings: [
            {
              sourceLabels: ['__name__'],
              regex: 'istio_.*',
              action: 'keep',
            },
            // drop instance label, we have the pod name
            {
              action: 'labeldrop',
              regex: 'instance',
            },
          ],
          relabelings: [
            {
              action: 'keep',
              sourceLabels: ['__meta_kubernetes_pod_container_name'],
              regex: 'istio-proxy',
            },
            {
              action: 'replace',
              regex: '(\\d+);(([A-Fa-f0-9]{1,4}::?){1,7}[A-Fa-f0-9]{1,4})',
              replacement: '[$2]:$1',
              sourceLabels: [
                '__meta_kubernetes_pod_annotation_prometheus_io_port',
                '__meta_kubernetes_pod_ip',
              ],
              targetLabel: '__address__',
            },
            {
              action: 'replace',
              regex: '(\\d+);((([0-9]+?)(\\.|$)){4})',
              replacement: '$2:$1',
              sourceLabels: [
                '__meta_kubernetes_pod_annotation_prometheus_io_port',
                '__meta_kubernetes_pod_ip',
              ],
              targetLabel: '__address__',
            },
            {
              action: 'labeldrop',
              regex: '__meta_kubernetes_pod_label_(.+)',
            },
            {
              sourceLabels: ['__meta_kubernetes_namespace'],
              action: 'replace',
              targetLabel: 'namespace',
            },
          ],
        },
      ],
    },
    {
      dependsOn,
    }
  );
  const gateway = new PodMonitor(
    `istio-gateway-monitor`,
    ingressNs.ns.metadata.name,
    {
      matchLabels: {
        istio: 'ingress',
      },
      podMetricsEndpoints: [{ port: 'http-envoy-prom', path: '/stats/prometheus' }],
      namespaces: [ingressNs.ns.metadata.name],
    },
    {
      dependsOn,
    }
  );
  return [svc, sidecar, gateway];
}
