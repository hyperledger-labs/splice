import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { loadIPRanges } from '../../common';
import { clusterBasename } from './config';

function configureIstioBase(ns: k8s.core.v1.Namespace): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    'istio-base',
    {
      name: 'istio-base',
      chart: 'base',
      namespace: ns.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
    },
    {
      dependsOn: [ns],
    }
  );
}

function configureIstiod(
  ingressNs: k8s.core.v1.Namespace,
  base: k8s.helm.v3.Release
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    'istiod',
    {
      name: 'istiod',
      chart: 'istiod',
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        global: {
          istioNamespace: 'cluster-ingress',
        },
        meshConfig: {
          // Turns on envoy logging
          accessLogFile: '/dev/stdout',
        },
      },
    },
    {
      dependsOn: [ingressNs, base],
    }
  );
}

function ingressPort(
  name: string,
  port: number
): { name: string; port: number; targetPort: number; protocol: string } {
  return {
    name: name,
    port: port,
    targetPort: port,
    protocol: 'TCP',
  };
}

// Note that despite the helm chart name being "gateway", this does not actually
// deploy an istio "gateway" resource, but rather the istio-ingress LoadBalancer
// service and the istio-ingress pod.
function configureGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>,
  istiod: k8s.helm.v3.Release
) {
  const externalIPRanges = loadIPRanges();
  return new k8s.helm.v3.Release(
    'istio-ingress',
    {
      name: 'istio-ingress',
      chart: 'gateway',
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        service: {
          loadBalancerIP: ingressIp,
          loadBalancerSourceRanges: externalIPRanges,
          ports: [
            ingressPort('status-port', 15021), // istio default
            ingressPort('http2', 80),
            ingressPort('https', 443),
            ingressPort('grpc-cd-pub-api', 5008),
            ingressPort('grpc-cd-adm-api', 5009),
            ingressPort('cd-metrics', 10313),
            ingressPort('grpc-svcp-adm', 5002),
            ingressPort('grpc-svcp-lg', 5001),
            ingressPort('svcp-metrics', 10013),
            ingressPort('grpc-val1-adm', 5102),
            ingressPort('grpc-val1-lg', 5101),
            ingressPort('val1-metrics', 10113),
            ingressPort('val1-lg-gw', 6101),
            ingressPort('grpc-swd-pub', 5108),
            ingressPort('grpc-swd-adm', 5109),
            ingressPort('swd-metrics', 10413),
            ingressPort('grpc-sw-adm', 5202),
            ingressPort('grpc-sw-lg', 5201),
            ingressPort('sw-metrics', 10213),
            ingressPort('sw-lg-gw', 6201),
            // see notes when installing a CometBft node in the full deployment
            ingressPort('cometbft1-gw', 26656),
            ingressPort('cometbft2-gw', 26666),
            ingressPort('cometbft3-gw', 26676),
            ingressPort('cometbft4-gw', 26686),
            ingressPort('cometbft5-gw', 26696),
          ],
        },
      },
    },
    {
      dependsOn: [ingressNs, istiod],
    }
  );
}

function configureGateway(
  ingressNs: k8s.core.v1.Namespace,
  gwSvc: k8s.helm.v3.Release
): k8s.helm.v3.Release {
  const repo_root = process.env.REPO_ROOT;
  return new k8s.helm.v3.Release(
    'cluster-gateway',
    {
      name: 'cluster-gateway',
      namespace: ingressNs.metadata.name,
      chart: repo_root + '/cluster/helm/cn-istio-gateway/',
      values: {
        cluster: {
          hostname: `${clusterBasename}.network.canton.global`,
          basename: clusterBasename,
        },
      },
    },
    {
      dependsOn: [gwSvc],
    }
  );
}

export function configureIstio(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>
): k8s.helm.v3.Release {
  const nsName = 'istio-system';
  const istioSystemNs = new k8s.core.v1.Namespace(nsName, {
    metadata: {
      name: nsName,
    },
  });
  const base = configureIstioBase(istioSystemNs);
  const istiod = configureIstiod(ingressNs, base);
  const gwSvc = configureGatewayService(ingressNs, ingressIp, istiod);
  const gw = configureGateway(ingressNs, gwSvc);
  return gw;
}
