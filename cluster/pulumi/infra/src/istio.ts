// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  allSvsToDeploy,
  coreSvsToDeploy,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/svConfigs';
import { cometBFTExternalPort } from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/synchronizer/cometbftConfig';
import { spliceConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { PodMonitor, ServiceMonitor } from '@lfdecentralizedtrust/splice-pulumi-common/src/metrics';

import {
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  GCP_PROJECT,
  GCP_ZONE,
  getDnsNames,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
  isDevNet,
  isMainNet,
} from '../../common';
import { clusterBasename, infraConfig, loadIPRanges } from './config';

export const istioVersion = {
  istio: '1.26.1',
  //   updated from https://grafana.com/orgs/istio/dashboards, must be updated on each istio version
  dashboards: {
    general: 259,
    wasm: 216,
  },
};

// dsoSize + number of extra SVs added via config.yaml
const numCoreSvsToDeploy = coreSvsToDeploy.length;

function configureIstioBase(
  ns: k8s.core.v1.Namespace,
  istioDNamespace: k8s.core.v1.Namespace
): k8s.helm.v3.Release {
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
      dependsOn: [ns],
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
      // https://artifacthub.io/packages/helm/istio-official/istiod
      values: {
        autoscaleMin: 2,
        autoscaleMax: 20,
        ...infraAffinityAndTolerations,
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
        // https://istio.io/latest/docs/reference/config/istio.mesh.v1alpha1/
        meshConfig: {
          // Uncomment to turn on access logging across the entire cluster (we disabled it by default to reduce cost):
          // accessLogFile: '/dev/stdout',
          // taken from https://github.com/istio/istio/issues/37682
          accessLogFile: '',
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
    location: GCP_ZONE,
  });
  // The loopback traffic would be prevented by our policy. To still allow it, we
  // add the node pool ip ranges to the list.
  // eslint-disable-next-line promise/prefer-await-to-then
  const internalIPRanges = cluster.then(c =>
    c.nodePools.map(p => p.networkConfigs.map(c => c.podIpv4CidrBlock)).flat()
  );
  const externalIPRanges = loadIPRanges();
  return configureGatewayService(
    ingressNs,
    ingressIp,
    pulumi.all([externalIPRanges, internalIPRanges]).apply(([a, b]) => a.concat(b)),
    pulumi.output(['0.0.0.0/0']),
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
    ],
    istiod,
    ''
  );
}

function configureCometBFTGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingressIp: pulumi.Output<string>,
  istiod: k8s.helm.v3.Release
) {
  const externalIPRanges = loadIPRanges(true);
  const numMigrations = DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1;
  // For DevNet-like clusters, we always assume at least 4 SVs to reduce churn on the gateway definition,
  // and support easily deploying without refreshing the infra stack.
  const numSVs = numCoreSvsToDeploy < 4 && isDevNet ? 4 : numCoreSvsToDeploy;

  const cometBftIngressPorts = Array.from({ length: numMigrations }, (_, i) => i).flatMap(
    migration => {
      const res = Array.from({ length: numSVs }, (_, node) => node).map(node =>
        ingressPort(
          `cometbft-${migration}-${node + 1}-gw`,
          cometBFTExternalPort(migration, node + 1)
        )
      );
      if (!isMainNet) {
        // For non-mainnet clusters, include "node 0" for the sv runbook
        res.unshift(ingressPort(`cometbft-${migration}-0-gw`, cometBFTExternalPort(migration, 0)));
      }
      return res;
    }
  );
  return configureGatewayService(
    ingressNs,
    ingressIp,
    pulumi.output(['0.0.0.0/0']),
    externalIPRanges,
    cometBftIngressPorts,
    istiod,
    '-cometbft'
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
  externalIPRangesInIstio: pulumi.Output<string[]>,
  externalIPRangesInLB: pulumi.Output<string[]>,
  ingressPorts: IngressPort[],
  istiod: k8s.helm.v3.Release,
  suffix: string
) {
  // We limit source IPs in two ways:
  // - For most traffic, we use istio instead of through loadBalancerSourceRanges as the latter has a size limit.
  //   These IPs should be provided in externalIPRangesInIstio.
  //   See https://github.com/DACH-NY/canton-network-internal/issues/626
  // - For cometbft traffic, which is tcp traffic, we failed to use istio policies, so we route it through a dedicated
  //   LaodBalancer service that uses loadBalancerSourceRanges. The size limit is not an issue as we need only SV IPs.
  //   These IPs should be provided in externalIPRangesInLB.

  const istioPolicies = istioAccessPolicies(ingressNs, externalIPRangesInIstio, suffix);
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
          loadBalancerSourceRanges: externalIPRangesInLB,
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
      dependsOn: istioPolicies
        ? istioPolicies.apply(policies => {
            const base: pulumi.Resource[] = [ingressNs, istiod];
            return base.concat(policies);
          })
        : [ingressNs, istiod],
    }
  );
  if (infraConfig.istio.enableIngressAccessLogging) {
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
  }
  return gateway;
}

function configureGateway(
  ingressNs: ExactNamespace,
  gwSvc: k8s.helm.v3.Release,
  cometBftSvc: k8s.helm.v3.Release
): k8s.apiextensions.CustomResource[] {
  const hosts = [
    getDnsNames().cantonDnsName,
    `*.${getDnsNames().cantonDnsName}`,
    getDnsNames().daDnsName,
    `*.${getDnsNames().daDnsName}`,
  ];
  const httpGw = new k8s.apiextensions.CustomResource(
    'cn-http-gateway',
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'Gateway',
      metadata: {
        name: 'cn-http-gateway',
        namespace: ingressNs.ns.metadata.name,
      },
      spec: {
        selector: {
          app: 'istio-ingress',
          istio: 'ingress',
        },
        servers: [
          {
            hosts,
            port: {
              name: 'http',
              number: 80,
              protocol: 'HTTP',
            },
            tls: {
              httpsRedirect: true,
            },
          },
          {
            hosts,
            port: {
              name: 'https',
              number: 443,
              protocol: 'HTTPS',
            },
            tls: {
              mode: 'SIMPLE',
              credentialName: `cn-${clusterBasename}net-tls`,
            },
          },
        ],
      },
    },
    {
      dependsOn: [gwSvc],
    }
  );

  const numMigrations = DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1;
  // For DevNet-like clusters, we always assume at least 4 SVs (not including sv-runbook) to reduce churn on the gateway definition,
  // and support easily deploying without refreshing the infra stack.
  const numSVs = numCoreSvsToDeploy < 4 && isDevNet ? 4 : numCoreSvsToDeploy;

  const server = (migration: number, node: number) => ({
    // We cannot really distinguish TCP traffic by hostname, so configuring to "*" to be explicit about that
    hosts: ['*'],
    port: {
      name: `cometbft-${migration}-${node}-gw`,
      number: cometBFTExternalPort(migration, node),
      protocol: 'TCP',
    },
  });

  const servers = Array.from({ length: numMigrations }, (_, i) => i).flatMap(migration => {
    const ret = Array.from({ length: numSVs }, (_, node) => node).map(node =>
      server(migration, node + 1)
    );
    if (!isMainNet) {
      // For non-mainnet clusters, include "node 0" for the sv runbook
      ret.unshift(server(migration, 0));
    }
    return ret;
  });

  const appsGw = new k8s.apiextensions.CustomResource(
    'cn-apps-gateway',
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'Gateway',
      metadata: {
        name: 'cn-apps-gateway',
        namespace: ingressNs.ns.metadata.name,
      },
      spec: {
        selector: {
          app: 'istio-ingress-cometbft',
          istio: 'ingress-cometbft',
        },
        servers,
      },
    },
    {
      dependsOn: [cometBftSvc],
    }
  );
  return [httpGw, appsGw];
}

function configureDocsAndReleases(
  enableGcsProxy: boolean,
  dependsOn: pulumi.Resource[]
): k8s.apiextensions.CustomResource[] {
  const gcsProxyPath: {
    match: { port: number; uri?: { prefix: string } }[];
    route: { destination: { port: { number: number }; host: string } }[];
  }[] = enableGcsProxy
    ? [
        {
          match: [
            {
              port: 443,
              uri: {
                prefix: '/cn-release-bundles',
              },
            },
          ],
          route: [
            {
              destination: {
                port: {
                  number: 8080,
                },
                host: 'gcs-proxy.docs.svc.cluster.local',
              },
            },
          ],
        },
      ]
    : [];
  return [
    new k8s.apiextensions.CustomResource(
      'cluster-docs-releases',
      {
        apiVersion: 'networking.istio.io/v1alpha3',
        kind: 'VirtualService',
        metadata: {
          name: 'cluster-docs-releases',
          namespace: 'cluster-ingress',
        },
        spec: {
          hosts: [getDnsNames().cantonDnsName].concat(
            CLUSTER_HOSTNAME == getDnsNames().daDnsName ? [getDnsNames().daDnsName] : []
          ),
          gateways: ['cn-http-gateway'],
          http: gcsProxyPath.concat([
            {
              match: [
                {
                  port: 443,
                },
              ],
              route: [
                {
                  destination: {
                    port: {
                      number: 80,
                    },
                    host: 'docs.docs.svc.cluster.local',
                  },
                },
              ],
            },
          ]),
        },
      },
      { dependsOn }
    ),
  ];
}

function configurePublicInfo(ingressNs: k8s.core.v1.Namespace): k8s.apiextensions.CustomResource[] {
  return spliceConfig.pulumiProjectConfig.hasPublicInfo
    ? [
        new k8s.apiextensions.CustomResource('allow-sv-info', {
          apiVersion: 'security.istio.io/v1beta1',
          kind: 'AuthorizationPolicy',
          metadata: {
            name: 'allow-sv-info',
            namespace: ingressNs.metadata.name,
          },
          spec: {
            selector: {
              matchLabels: {
                istio: 'ingress',
              },
            },
            action: 'ALLOW',
            rules: [
              {
                to: [
                  {
                    operation: {
                      hosts: [
                        // We could also have done `info.sv*.whatever` here but enumerating what we expect seems slightly more secure
                        ...new Set(
                          allSvsToDeploy
                            .map(sv => [
                              `info.${sv.ingressName}.${getDnsNames().cantonDnsName}`,
                              `info.${sv.ingressName}.${getDnsNames().daDnsName}`,
                            ])
                            .flat()
                        ),
                      ],
                    },
                  },
                ],
              },
            ],
          },
        }),
      ]
    : [];
}

export function configureIstio(
  ingressNs: ExactNamespace,
  ingressIp: pulumi.Output<string>,
  cometBftIngressIp: pulumi.Output<string>
): pulumi.Resource[] {
  const nsName = 'istio-system';
  const istioSystemNs = new k8s.core.v1.Namespace(nsName, {
    metadata: {
      name: nsName,
    },
  });
  const base = configureIstioBase(istioSystemNs, ingressNs.ns);
  const istiod = configureIstiod(ingressNs.ns, base);
  const gwSvc = configureInternalGatewayService(ingressNs.ns, ingressIp, istiod);
  const cometBftSvc = configureCometBFTGatewayService(ingressNs.ns, cometBftIngressIp, istiod);
  const gateways = configureGateway(ingressNs, gwSvc, cometBftSvc);
  const docsAndReleases = configureDocsAndReleases(true, gateways);
  const publicInfo = configurePublicInfo(ingressNs.ns);
  return [...gateways, ...docsAndReleases, ...publicInfo];
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
