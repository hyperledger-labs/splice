// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { local } from '@pulumi/command';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
import { PodMonitor, ServiceMonitor } from 'splice-pulumi-common/src/metrics';

import {
  activeVersion,
  CLUSTER_NAME,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  GCP_PROJECT,
  getDnsNames,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
  InstalledHelmChart,
  installSpliceHelmChart,
  MOCK_SPLICE_ROOT,
  SPLICE_ROOT,
} from '../../common';
import { clusterBasename, loadIPRanges } from './config';

export const istioVersion = {
  istio: '1.26.1',
  //   updated from https://grafana.com/orgs/istio/dashboards, must be updated on each istio version
  dashboards: {
    general: 259,
    wasm: 216,
  },
};

function configureIstioBase(
  ns: k8s.core.v1.Namespace,
  istioDNamespace: k8s.core.v1.Namespace
): k8s.helm.v3.Release {
  const root = MOCK_SPLICE_ROOT || SPLICE_ROOT;
  const path = `${root}/cluster/pulumi/infra/migrate-istio.sh`;
  const migration = new local.Command(`migrate-istio-crds`, {
    create: path,
  });

  return new k8s.helm.v3.Release(
    'istio-base',
    {
      name: 'istio-base',
      chart: 'base',
      version: istioVersion.istio,
      namespace: ns.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        global: {
          istioNamespace: istioDNamespace.metadata.name,
        },
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [ns, migration],
    }
  );
}

function configureIstiod(
  ingressNs: k8s.core.v1.Namespace,
  base: k8s.helm.v3.Release
): k8s.helm.v3.Release {
  const istiodRelease = new k8s.helm.v3.Release(
    'istiod',
    {
      name: 'istiod',
      chart: 'istiod',
      version: istioVersion.istio,
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        global: {
          istioNamespace: ingressNs.metadata.name,
          logAsJson: true,
          proxy: {
            // disable traffic proxying for the postgres port and CometBFT RPC port
            excludeInboundPorts: '5432,26657',
            excludeOutboundPorts: '5432,26657',
            resources: {
              limits: {
                memory: '4096Mi',
              },
            },
          },
        },
        pilot: {
          autoscaleMax: 10,
          ...infraAffinityAndTolerations,
        },
        meshConfig: {
          // Uncomment to turn on access logging across the entire cluster (we disabled it by default to reduce cost):
          // accessLogFile: '/dev/stdout',
          // taken from https://github.com/istio/istio/issues/37682
          accessLogEncoding: 'JSON',
          // https://istio.io/latest/docs/ops/integrations/prometheus/#option-1-metrics-merging  disable as we don't use annotations
          enablePrometheusMerge: false,
          defaultConfig: {
            // It is expected that a single load balancer (GCP NLB) is used in front of K8s.
            // https://istio.io/latest/docs/tasks/security/authorization/authz-ingress/#http-https
            // Also see:
            // https://istio.io/latest/docs/ops/configuration/traffic-management/network-topologies/#configuring-x-forwarded-for-headers
            // This controls the value populated by the ingress gateway in the X-Envoy-External-Address header which can be reliably used
            // by the upstream services to access client’s original IP address.
            gatewayTopology: {
              numTrustedProxies: 1,
            },
            // wait for the istio container to start before starting apps to avoid network errors
            holdApplicationUntilProxyStarts: true,
          },
          // We have clients retry so we disable istio’s automatic retries.
          defaultHttpRetryPolicy: {
            attempts: 0,
          },
        },
        telemetry: {
          enabled: true,
          v2: {
            enabled: true,
            prometheus: {
              enabled: true,
              //Prometheus goes brrrr https://github.com/istio/istio/issues/35414
              configOverride: {
                inboundSidecar: {
                  disable_host_header_fallback: true,
                },
                outboundSidecar: {
                  disable_host_header_fallback: true,
                },
                gateway: {
                  disable_host_header_fallback: true,
                },
              },
            },
          },
        },
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [ingressNs, base],
    }
  );
  return istiodRelease;
}

type IngressPort = {
  name: string;
  port: number;
  targetPort: number;
  protocol: string;
};

function ingressPort(name: string, port: number): IngressPort {
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
function configureInternalGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>,
  istiod: k8s.helm.v3.Release
) {
  const cluster = gcp.container.getCluster({
    name: CLUSTER_NAME,
    project: GCP_PROJECT,
  });
  // The loopback traffic would be prevented by our policy. To still allow it, we
  // add the node pool ip ranges to the list.
  // eslint-disable-next-line promise/prefer-await-to-then
  const internalIPRanges = cluster.then(c =>
    c.nodePools.map(p => p.networkConfigs.map(c => c.podIpv4CidrBlock)).flat()
  );
  const externalIPRanges = loadIPRanges();
  // see notes when installing a CometBft node in the full deployment
  const cometBftIngressPorts = DecentralizedSynchronizerUpgradeConfig.runningMigrations()
    .map(m => m.id)
    .flatMap((domain: number) => {
      return Array.from(Array(10).keys()).map(node => {
        return ingressPort(`cometbft-${domain}-${node}-gw`, Number(`26${domain}${node}6`));
      });
    });
  return configureGatewayService(
    ingressNs,
    ingressIp,
    pulumi.all([externalIPRanges, internalIPRanges]).apply(([a, b]) => a.concat(b)),
    [
      ingressPort('grpc-cd-pub-api', 5008),
      ingressPort('grpc-cs-p2p-api', 5010),
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
    ].concat(cometBftIngressPorts),
    istiod,
    ''
  );
}

function configurePublicGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>,
  istiod: k8s.helm.v3.Release
) {
  new k8s.apiextensions.CustomResource(`public-request-authentication`, {
    apiVersion: 'security.istio.io/v1',
    kind: 'RequestAuthentication',
    metadata: {
      name: 'public-request-authentication',
      namespace: ingressNs.metadata.name,
    },
    spec: {
      selector: {
        matchLabels: {
          istio: 'ingress-public',
        },
      },
      jwtRules: [
        {
          issuer: 'https://canton-network-ci.example.com',
          // Find details on the keys here https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit#heading=h.h81kh9iplwtp
          jwks: '{"keys": [{"kty":"RSA","n":"rX_TFg7BFsaQ4st9NrPiN4gc_sZmhifgEczn6CCedKKOTYouO7ik9KTg0eTfQN2qSU-2L4KYX4KbK2T3e6CYsWDB6UjZYdhEtfj_X_QyIQ8hBVKGoNpL6WJFvzALPR5ILokzp9kDy0oV9-SqC91lS-ai2sHED14uS4NVfw9xk9toZG1stOm4JmfzOyAB3ksBrTfefKaIyKguINOJi2lGCqK9hnWbGJM2OHFmzEle4djrJub9qRCEkHBejPWmHrdN1zB2FZlWVA_Ze8tqBf5K9xx1cIn0cTWETEIWPhLu8pk_hFan1YmMOiBpjsOlg2e6f_m0dvhBSkqqieVFQBka6iocfLGWJFRBHTwgFw9-PIMTtb0l42uIGzKTo1XrvwMSqy4rff028ZLkbxu6OmFHCm4gRR6wlXF4ha6pTkS-vjFVdn2pL09-6jLD7CbNf5Di8RwvdO3puSp_ZExGb8UapgjW3sonlXiMxz1VAYTOYb4YIRSWGKafyBrNB5MGVuqgvK_ZjBzBvax6wSAU6ldcuHiGfS786FH2QwA47Smo2ewPfKpO2ePOmkvNpleT817BStbFtZD8K9y7Pf0QiX1Hk4DA7N_oQp3hrgW7U9Dy0hIh2OflMnFFEdN51fV-89tdIAKTd1rn3NwTqRcTDH1-GvmLfZTWH2-ZOgjizWFPsqE","e":"AQAB","ext":true,"kid":"eb3d58621c3c7fc606386139a","alg":"RS256","use":"sig"}]}',
        },
      ],
    },
  });
  new k8s.apiextensions.CustomResource(`public-request-authorization`, {
    apiVersion: 'security.istio.io/v1beta1',
    kind: 'AuthorizationPolicy',
    metadata: {
      name: 'public-request-authorization',
      namespace: ingressNs.metadata.name,
    },
    spec: {
      selector: {
        matchLabels: {
          istio: 'ingress-public',
        },
      },
      action: 'ALLOW',
      rules: (
        [
          {
            from: [
              {
                source: {
                  requestPrincipals: ['https://canton-network-ci.example.com/canton-network-ci'],
                },
              },
            ],
          },
          {
            to: [
              {
                // Paths that do not require authentication at Istio.
                operation: {
                  paths: [
                    '/grafana/api/serviceaccounts',
                    '/grafana/api/serviceaccounts/*',
                    '/grafana/api/alertmanager/grafana/api/v2/silences',
                  ],
                },
              },
            ],
          },
        ] as unknown[]
      ).concat(
        spliceConfig.pulumiProjectConfig.hasPublicDocs
          ? [
              {
                to: [
                  {
                    operation: {
                      hosts: [
                        ...new Set([getDnsNames().cantonDnsName, getDnsNames().daDnsName]),
                      ].map(host => `docs.${host}`),
                    },
                  },
                ],
              },
            ]
          : []
      ),
    },
  });
  return configureGatewayService(
    ingressNs,
    ingressIp,
    pulumi.output(['0.0.0.0/0']),
    [],
    istiod,
    '-public'
  );
}

const istioApiVersion = 'security.istio.io/v1beta1';

function istioAccessPolicies(
  ingressNs: k8s.core.v1.Namespace,
  externalIPRanges: pulumi.Output<string[]>,
  suffix: string
) {
  const selector = {
    matchLabels: {
      app: `istio-ingress${suffix}`,
    },
  };
  const defaultDenyAll = new k8s.apiextensions.CustomResource(
    `istio-access-policy-deny-all${suffix}`,
    {
      apiVersion: istioApiVersion,
      kind: 'AuthorizationPolicy',
      metadata: {
        name: `istio-access-policy-deny-all${suffix}`,
        namespace: ingressNs.metadata.name,
      },
      // empty spec is deny all
      spec: { selector },
    }
  );
  return externalIPRanges.apply(ipRanges => {
    // There doesn't seem to be an istio-level limit on number of IP lists but at some point we probably hit some k8s limits on the size of a definition so we split it into 100 IP ranges per policy.
    const chunkSize = 100;
    const chunks = Array.from({ length: Math.ceil(ipRanges.length / chunkSize) }, (_, i) =>
      ipRanges.slice(i * chunkSize, i * chunkSize + chunkSize)
    );
    const policies = chunks.map(
      (chunk, i) =>
        new k8s.apiextensions.CustomResource(`istio-access-policy-allow${suffix}-${i}`, {
          apiVersion: istioApiVersion,
          kind: 'AuthorizationPolicy',
          metadata: {
            name: `istio-access-policy-allow${suffix}-${i}`,
            namespace: ingressNs.metadata.name,
          },
          spec: {
            selector,
            action: 'ALLOW',
            rules: [{ from: [{ source: { remoteIpBlocks: chunk } }] }],
          },
        })
    );
    return [defaultDenyAll].concat(policies);
  });
}

// Note that despite the helm chart name being "gateway", this does not actually
// deploy an istio "gateway" resource, but rather the istio-ingress LoadBalancer
// service and the istio-ingress pod.
function configureGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>,
  externalIPRanges: pulumi.Output<string[]>,
  ingressPorts: IngressPort[],
  istiod: k8s.helm.v3.Release,
  suffix: string
) {
  const istioPolicies = istioAccessPolicies(ingressNs, externalIPRanges, suffix);
  const gateway = new k8s.helm.v3.Release(
    `istio-ingress${suffix}`,
    {
      name: `istio-ingress${suffix}`,
      chart: 'gateway',
      version: istioVersion.istio,
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://istio-release.storage.googleapis.com/charts',
      },
      values: {
        resources: {
          requests: {
            cpu: '500m',
            memory: '512Mi',
          },
          limits: {
            cpu: '4',
            memory: '2024Mi',
          },
        },
        autoscaling: {
          maxReplicas: 15,
        },
        podDisruptionBudget: {
          maxUnavailable: 1,
        },
        service: {
          loadBalancerIP: ingressIp,
          // We limit IPs using istio instead of through loadBalancerSourceRanges as the latter has a size limit.
          // See https://github.com/DACH-NY/canton-network-internal/issues/626
          loadBalancerSourceRanges: ['0.0.0.0/0'],
          // See https://istio.io/latest/docs/tasks/security/authorization/authz-ingress/#network
          // If you are using a TCP/UDP network load balancer that preserves the client IP address ..
          // then you can use the externalTrafficPolicy: Local setting to also preserve the client IP inside Kubernetes by bypassing kube-proxy
          // and preventing it from sending traffic to other nodes.
          externalTrafficPolicy: 'Local',
          ports: [
            ingressPort('status-port', 15021), // istio default
            ingressPort('http2', 80),
            ingressPort('https', 443),
          ].concat(ingressPorts),
        },
        ...infraAffinityAndTolerations,
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: istioPolicies.apply(policies => {
        const base: pulumi.Resource[] = [ingressNs, istiod];
        return base.concat(policies);
      }),
    }
  );
  // Turn on envoy access logging on the ingress gateway
  new k8s.apiextensions.CustomResource(`access-logging${suffix}`, {
    apiVersion: 'telemetry.istio.io/v1alpha1',
    kind: 'Telemetry',
    metadata: {
      name: `access-logging${suffix}`,
      namespace: ingressNs.metadata.name,
    },
    spec: {
      accessLogging: [
        {
          providers: [
            {
              name: 'envoy',
            },
          ],
        },
      ],
      selector: {
        matchLabels: {
          app: `istio-ingress${suffix}`,
        },
      },
    },
  });
  return gateway;
}

function configureGateway(
  ingressNs: ExactNamespace,
  gwSvc: k8s.helm.v3.Release,
  publicGwSvc: k8s.helm.v3.Release
): InstalledHelmChart {
  return installSpliceHelmChart(
    ingressNs,
    'cluster-gateway',
    'splice-istio-gateway',
    {
      cluster: {
        cantonHostname: getDnsNames().cantonDnsName,
        daHostname: getDnsNames().daDnsName,
        basename: clusterBasename,
      },
      cometbftPorts: {
        // This ensures the loopback exposes the right ports. We need a +1 since the helm chart does an exclusive
        domains: DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1,
      },
      enableGcsProxy: true,
      publicDocs: spliceConfig.pulumiProjectConfig.hasPublicDocs,
    },
    activeVersion,
    {
      dependsOn: [gwSvc, publicGwSvc],
    },
    false,
    infraAffinityAndTolerations
  );
}

export function configureIstio(
  ingressNs: ExactNamespace,
  ingressIp: pulumi.Output<string>,
  publicIngressIp: pulumi.Output<string>
): InstalledHelmChart {
  const nsName = 'istio-system';
  const istioSystemNs = new k8s.core.v1.Namespace(nsName, {
    metadata: {
      name: nsName,
    },
  });
  const base = configureIstioBase(istioSystemNs, ingressNs.ns);
  const istiod = configureIstiod(ingressNs.ns, base);
  const gwSvc = configureInternalGatewayService(ingressNs.ns, ingressIp, istiod);
  const publicGwSvc = configurePublicGatewayService(ingressNs.ns, publicIngressIp, istiod);
  return configureGateway(ingressNs, gwSvc, publicGwSvc);
}

export function istioMonitoring(
  ingressNs: ExactNamespace,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  const svc = new ServiceMonitor(
    'istiod-service-monitor',
    {
      istio: 'pilot',
    },
    'http-monitoring',
    ingressNs.ns.metadata.name,
    { dependsOn }
  );

  const sidecar = new PodMonitor(
    `istio-sidecar-monitor`,
    {
      'security.istio.io/tlsMode': 'istio',
    },
    //https://github.com/istio/istio/blob/master/samples/addons/extras/prometheus-operator.yaml#L16
    [
      {
        port: 'http-envoy-prom',
        path: '/stats/prometheus',
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
            action: 'labeldrop',
            regex: 'instance',
          },
          {
            sourceLabels: ['__meta_kubernetes_namespace'],
            action: 'replace',
            targetLabel: 'namespace',
          },
        ],
      },
    ],
    ingressNs.ns.metadata.name,
    {
      dependsOn,
    }
  );
  const gateway = new PodMonitor(
    `istio-gateway-monitor`,
    {
      istio: 'ingress',
    },
    [{ port: 'http-envoy-prom', path: '/stats/prometheus' }],
    ingressNs.ns.metadata.name,
    {
      dependsOn,
    }
  );
  return [svc, sidecar, gateway];
}
