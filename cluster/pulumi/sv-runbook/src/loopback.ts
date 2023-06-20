import * as k8s from '@pulumi/kubernetes';
import { ExactNamespace, ingressPort } from 'cn-pulumi-common';

import { installCNSVHelmChart } from './helm';

export function installLoopback(
  namespace: ExactNamespace,
  clusterBasename: string,
  localCharts: boolean,
  version: string | undefined
): k8s.helm.v3.Release {
  new k8s.helm.v3.Release(
    'istio-egressgateway',
    {
      name: 'istio-egressgateway',
      chart: 'gateway',
      namespace: namespace.logicalName,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        service: {
          type: 'ClusterIP',
          ports: [
            ingressPort('status-port', 15021), // istio default
            ingressPort('http2', 80),
            ingressPort('https', 443),
            ingressPort('grpc-svc', 5005),
            ingressPort('grpc-domain', 5008),
            ingressPort('http-scan', 5012),
            // see notes when installing a CometBft node in the full deployment
            ingressPort('cometbft-1', 26656),
            ingressPort('cometbft-2', 26666),
            ingressPort('cometbft-3', 26676),
            ingressPort('cometbft-4', 26686),
            ingressPort('cometbft-sv', 26696),
          ],
        },
      },
    },
    {
      dependsOn: [namespace.ns],
    }
  );

  return installCNSVHelmChart(
    namespace,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: clusterBasename,
      },
    },
    localCharts,
    version,
    [namespace.ns]
  );
}
