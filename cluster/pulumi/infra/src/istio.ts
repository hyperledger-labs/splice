// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  allSvsToDeployBasic,
  coreSvsToDeployBasic,
} from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';
import { cometBFTExternalPort } from '@canton-network/splice-pulumi-common-sv/src/synchronizer/cometbftConfig';
import { gkeL7GatewayNumTrustedProxies } from '@canton-network/splice-pulumi-common/src/ratelimit/envoyRateLimiter';
import { rateLimitResponseHeaders } from '@canton-network/splice-pulumi-common/src/ratelimit/rateLimitHeaders';
import { mergeWith } from 'lodash';
import { z } from 'zod';

import {
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  GCP_PROJECT,
  GCP_ZONE,
  getDnsNames,
  HELM_MAX_HISTORY_SIZE,
  infraKubernetesScheduling,
  isDevNet,
  isMainNet,
} from '../../common';
import { clusterBasename, flowControlConfigSchema, infraConfig } from './config';
import { configureIstioGatewayPolicies, installAppWhitelisting } from './whitelisting';
import { loadInternalWhitelistedIps, loadIPRanges } from './whitelisting/ipRanges';
import { configurePublicInfo } from './whitelisting/publicInfo';
import { configurePublicTokenRegistry } from './whitelisting/publicTokenRegistry';

interface ConfiguredIstio {
  allResources: pulumi.Resource[];
  httpServiceName: string;
  istioResource: k8s.helm.v3.Release;
}

export const istioVersion = {
  istio: '1.30.5',
  //   updated from https://grafana.com/orgs/istio/dashboards, must be updated on each istio version
  dashboards: {
    general: 332, // https://grafana.com/grafana/dashboards/7645-istio-control-plane-dashboard/
    wasm: 290, // https://grafana.com/grafana/dashboards/13277-istio-wasm-extension-dashboard/
  },
};

// dsoSize + number of extra SVs added via config.yaml
const numCoreSvsToDeploy = coreSvsToDeployBasic.length;

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
        repo: 'https://blob.istio.io/istio-release/charts',
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
  // https://artifacthub.io/packages/helm/istio-official/istiod
  const defaultValues = {
    autoscaleMin: 2,
    autoscaleMax: 30,
    ...infraKubernetesScheduling,
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
      // taken from https://github.com/istio/istio/issues/37682
      accessLogFile: infraConfig.istio.enableClusterAccessLogging ? '/dev/stdout' : '',
      accessLogEncoding: 'JSON',
      // Changing istio access log default format to include trace_id:
      // - envoy access log configuration: https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log
      // - w3c docs for trace context: https://www.w3.org/TR/trace-context/#header-name
      accessLogFormat: JSON.stringify({
        trace_id: '%REQ(traceparent)%',
        authority: '%REQ(:AUTHORITY)%',
        bytes_received: '%BYTES_RECEIVED%',
        bytes_sent: '%BYTES_SENT%',
        downstream_local_address: '%DOWNSTREAM_LOCAL_ADDRESS%',
        downstream_remote_address: '%DOWNSTREAM_REMOTE_ADDRESS%',
        duration: '%DURATION%',
        method: '%REQ(:METHOD)%',
        path: '%REQ(X-ENVOY-ORIGINAL-PATH?:PATH)%',
        protocol: '%PROTOCOL%',
        request_id: '%REQ(X-REQUEST-ID)%',
        requested_server_name: '%REQUESTED_SERVER_NAME%',
        response_code: '%RESPONSE_CODE%',
        response_code_details: '%RESPONSE_CODE_DETAILS%',
        // gRPC calls always end with HTTP 200, the outcome is in the gRPC status: a rate limited
        // call is reported as `ResourceExhausted` (see `rate_limited_as_resource_exhausted`),
        // whereas a rate limited HTTP request is reported as response_code 429
        grpc_status: '%GRPC_STATUS(CAMEL_STRING)%',
        response_flags: '%RESPONSE_FLAGS%',
        start_time: '%START_TIME%',
        upstream_cluster: '%UPSTREAM_CLUSTER%',
        upstream_host: '%UPSTREAM_HOST%',
        upstream_local_address: '%UPSTREAM_LOCAL_ADDRESS%',
        upstream_service_time: '%RESP(X-ENVOY-UPSTREAM-SERVICE-TIME)%',
        user_agent: '%REQ(USER-AGENT)%',
        x_forwarded_for: '%REQ(X-FORWARDED-FOR)%',
        // the trusted client IP as determined by envoy (based on numTrustedProxies),
        // this is the header the apps use for per-client-IP rate limiting
        envoy_external_address: '%REQ(X-ENVOY-EXTERNAL-ADDRESS)%',
        // The address the per-client-IP rate limit buckets are keyed on: envoy's
        // `masked_remote_address` action masks the downstream remote address (the trusted,
        // XFF-derived client address) with the configured prefix length, which is /32 for
        // IPv4 and /128 for IPv6, i.e. the full address without the port.
        masked_remote_address: '%DOWNSTREAM_REMOTE_ADDRESS_WITHOUT_PORT%',
        // rate limiting fields, will show up in sidecar access logging
        // the value identifies the limit that rejected the request: `global`, `per_ip`,
        // `endpoint` or `endpoint_per_ip`, i.e. the same names as the `limiter` label on the
        // envoy_http_local_rate_limit_* metrics, which cannot attribute a single request to a limit
        local_rate_limited: '%RESP(x-local-rate-limit)%',
        rate_limit_limit: '%RESP(x-ratelimit-limit)%',
        rate_limit_remaining: '%RESP(x-ratelimit-remaining)%',
        rate_limit_reset: '%RESP(x-ratelimit-reset)%',
      }),
      // https://istio.io/latest/docs/ops/integrations/prometheus/#option-1-metrics-merging  disable as we don't use annotations
      enablePrometheusMerge: false,
      // https://istio.io/latest/docs/ops/best-practices/security/#path-normalization
      pathNormalization: {
        normalization: 'MERGE_SLASHES',
      },
      defaultConfig: {
        // The GCP NLB with externalTrafficPolicy: Local preserves the client's
        // source IP without adding X-Forwarded-For hops, so there are no trusted
        // proxies to account for. This ensures remoteIpBlocks in AuthorizationPolicy
        // uses the direct connection IP rather than the X-Forwarded-For header.
        // https://istio.io/latest/docs/tasks/security/authorization/authz-ingress/#network
        // By contrast, the GKE L7 Gateway path overrides this to 2 via pod annotation,
        // the minimum value per testing (as that gateway adds more Envoy hops).
        // https://istio.io/latest/docs/ops/configuration/traffic-management/network-topologies/#configuring-x-forwarded-for-headers
        gatewayTopology: {
          numTrustedProxies: 0,
        },
        // wait for the istio container to start before starting apps to avoid network errors
        holdApplicationUntilProxyStarts: true,
        // Export the local rate limit filter counters (enabled/ok/rate_limited/enforced).
        // Deliberately narrow: inclusionRegexps is *additive* on top of Istio's
        // defaults, so a broad regex here would blow up Prometheus cardinality.
        // docs: https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/local_rate_limit_filter#statistics
        proxyStatsMatcher: {
          inclusionRegexps: ['.*http_local_rate_limit.*'],
        },
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
  };
  const istiodRelease = new k8s.helm.v3.Release(
    'istiod',
    {
      name: 'istiod',
      chart: 'istiod',
      version: istioVersion.istio,
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://blob.istio.io/istio-release/charts',
      },
      values: mergeWith(
        defaultValues,
        infraConfig.istio.istiodValues,
        (_default: unknown, override: unknown) =>
          Array.isArray(_default) || Array.isArray(override) ? override : undefined
      ),
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [ingressNs, base],
    }
  );
  return istiodRelease;
}

// The pod must outlive the load balancer's backend draining window plus the time it
// takes for the NEG endpoint removal to propagate; Google suggests about a minute for
// the latter. The grace period then has to cover the preStop sleep, envoy's own drain
// and some buffer: 120 + 60 + 30 = 210.
const PRE_STOP_DRAIN_SECONDS = 120;
const TERMINATION_DRAIN_DURATION_SECONDS = 60;
const TERMINATION_GRACE_PERIOD_SECONDS = 210;

type IngressPort = {
  name: string;
  port: number;
  targetPort: number;
  protocol: string;
  appProtocol?: string;
};

function ingressPort(name: string, port: number, appProtocol?: string): IngressPort {
  return {
    name: name,
    port: port,
    targetPort: port,
    protocol: 'TCP',
    ...(appProtocol ? { appProtocol } : {}),
  };
}

/*
The result of configureInternalGatewayService is passed to configureGKEL7Gateway
as a dependency. This is important because reconfiguring the `istio-ingress` helm
release has to complete before creating that gateway. This works fine. However,
when turning off the flag, which causes the configureGKEL7Gateway gateway to be deleted,
this deletion should happen before reconfiguring the `istio-ingress` helm release.
However, pulumi up always tries to update the `istio-ingress` helm release first,
which is guaranteed to fail.

Changes that do not improve things at all:
- having replaceOnChanges include service.type
- having istio-ingress be cn-gke-l7-gateway's parent
 */

// Note that despite the helm chart name being "gateway", this does not actually
// deploy an istio "gateway" resource, but rather the istio-ingress LoadBalancer
// service and the istio-ingress pod.
function configureInternalGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  ingress: { ip: pulumi.Output<string>; viaGKEL7: false } | { viaGKEL7: true },
  istiod: k8s.helm.v3.Release,
  extraDependencies: pulumi.Resource[] = []
) {
  const cluster = gcp.container.getCluster({
    name: CLUSTER_NAME,
    project: GCP_PROJECT,
    location: GCP_ZONE,
  });
  // The loopback traffic would be prevented by our policy. To still allow it, we
  // add the node pool ip ranges to the list.
  // eslint-disable-next-line promise/prefer-await-to-then
  const gcpInternalIPRanges = cluster.then(c =>
    c.nodePools.map(p => p.networkConfigs.map(c => c.podIpv4CidrBlock)).flat()
  );
  const gatewayIPRanges = pulumi
    .all([loadInternalWhitelistedIps(), gcpInternalIPRanges])
    .apply(([a, b]) => a.concat(b));
  return configureGatewayService(
    ingressNs,
    gatewayIPRanges,
    ingress.viaGKEL7
      ? { type: 'ClusterIP' }
      : {
          type: 'LoadBalancer',
          ingressIp: ingress.ip,
          externalIPRangesInLB: pulumi.output(['0.0.0.0/0']),
        },
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
    '',
    extraDependencies
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

  const cometBftIngressPorts = Array.from(
    { length: Math.min(numMigrations, 10) },
    (_, i) => i
  ).flatMap(migration => {
    const res = Array.from({ length: numSVs }, (_, node) => node).map(node =>
      ingressPort(`cometbft-${migration}-${node + 1}-gw`, cometBFTExternalPort(migration, node + 1))
    );
    if (!isMainNet) {
      // For non-mainnet clusters, include "node 0" for the sv runbook
      res.unshift(ingressPort(`cometbft-${migration}-0-gw`, cometBFTExternalPort(migration, 0)));
    }
    return res;
  });
  return configureGatewayService(
    ingressNs,
    pulumi.output(['0.0.0.0/0']),
    { type: 'LoadBalancer', ingressIp, externalIPRangesInLB: externalIPRanges },
    cometBftIngressPorts,
    istiod,
    '-cometbft'
  );
}

// how gateway is configured: https://github.com/istio/istio/blob/master/manifests/charts/gateway/templates/service.yaml
type IstioGatewayVariant =
  | {
      type: 'LoadBalancer';
      ingressIp: pulumi.Output<string>;
      externalIPRangesInLB: pulumi.Output<string[]>;
    }
  | { type: 'ClusterIP' };

// Note that despite the helm chart name being "gateway", this does not actually
// deploy an istio "gateway" resource, but rather the istio-ingress LoadBalancer
// service and the istio-ingress pod.
function configureGatewayService(
  ingressNs: k8s.core.v1.Namespace,
  externalIPRangesInIstio: pulumi.Output<string[]>,
  gatewayVariant: IstioGatewayVariant,
  ingressPorts: IngressPort[],
  istiod: k8s.helm.v3.Release,
  suffix: string,
  extraDependencies: pulumi.Resource[] = []
) {
  // We limit source IPs in two ways:
  // - For most traffic, we use istio instead of through loadBalancerSourceRanges as the latter has a size limit.
  //   These IPs should be provided in externalIPRangesInIstio.
  //   See https://github.com/DACH-NY/canton-network-internal/issues/626
  // - For cometbft traffic, which is tcp traffic, we failed to use istio policies, so we route it through a dedicated
  //   LoadBalancer service that uses loadBalancerSourceRanges. The size limit is not an issue as we need only SV IPs.
  //   These IPs should be provided in externalIPRangesInLB.
  const istioPolicies = configureIstioGatewayPolicies(ingressNs, externalIPRangesInIstio, suffix);

  const { serviceValues, deploymentValues, port80Protocol } =
    gatewayVariant.type === 'LoadBalancer'
      ? {
          serviceValues: {
            // type's default is LoadBalancer; see values.yaml
            loadBalancerIP: gatewayVariant.ingressIp,
            loadBalancerSourceRanges: gatewayVariant.externalIPRangesInLB,
            // See https://istio.io/latest/docs/tasks/security/authorization/authz-ingress/#network
            // If you are using a TCP/UDP network load balancer that preserves the client IP address ..
            // then you can use the externalTrafficPolicy: Local setting to also preserve the client IP inside Kubernetes by bypassing kube-proxy
            // and preventing it from sending traffic to other nodes.
            externalTrafficPolicy: 'Local',
          },
          deploymentValues: {},
          port80Protocol: undefined,
        }
      : {
          // Create a ClusterIP Service for the istio ingress so the GKE L7 Gateway can
          // target it (the GKE controller will create NEGs for the service ports).
          serviceValues: {
            // without LoadBalancer, the istio Gateway will not create a public IP
            type: gatewayVariant.type,
          },
          deploymentValues: {
            podAnnotations: {
              'proxy.istio.io/config': JSON.stringify({
                // the 2 are an IP from the proxy-only subnet and the ingress IP itself.
                gatewayTopology: { numTrustedProxies: gkeL7GatewayNumTrustedProxies },
                // Once SIGTERM arrives, istio-agent drains envoy and then kills it after
                // this long; the default of 5s is short enough to cut requests that are
                // still in flight. Scoped to this pod rather than set in meshConfig, so
                // that app sidecar shutdown (and therefore rollout speed) is unaffected.
                terminationDrainDuration: `${TERMINATION_DRAIN_DURATION_SECONDS}s`,
              }),
            },
            // Behind the GKE L7 Gateway these pods are NEG endpoints. Deleting one (HPA
            // scale-down, rollout, node drain) removes it from the NEG, but the load
            // balancer keeps dispatching over its already pooled connections until that
            // removal propagates, so requests in flight at that moment fail. They surface
            // as `proxyStatus: error="connection_terminated"` in the load balancer logs,
            // with no matching app log when the connection is cut before the request is
            // forwarded.
            // The pod therefore has to keep serving until the drain has propagated:
            //   preStop >= backend service draining timeout + propagation latency
            //   terminationGracePeriod >= preStop + terminationDrainDuration + buffer
            // Values are Google's recommended starting point, and must stay consistent
            // with BACKEND_DRAINING_TIMEOUT_SECONDS in gcpLoadBalancer.ts.
            // https://cloud.google.com/kubernetes-engine/docs/troubleshooting/load-balancing#addressing_500_series_errors_with_negs_during_workload_scaling_in_gke
            lifecycle: {
              preStop: {
                exec: {
                  command: ['sleep', `${PRE_STOP_DRAIN_SECONDS}`],
                },
              },
            },
            terminationGracePeriodSeconds: TERMINATION_GRACE_PERIOD_SECONDS,
          },
          // force HTTP/2 (h2c) between GKE L7 Gateway and istio-ingress for
          // gRPC routes
          port80Protocol: 'kubernetes.io/h2c',
        };

  const gateway = new k8s.helm.v3.Release(
    `istio-ingress${suffix}`,
    {
      name: `istio-ingress${suffix}`,
      chart: 'gateway',
      version: istioVersion.istio,
      namespace: ingressNs.metadata.name,
      repositoryOpts: {
        repo: 'https://blob.istio.io/istio-release/charts',
      },
      values: {
        resources: {
          requests: {
            cpu: '500m',
            memory: '1024Mi',
          },
          limits: {
            cpu: '4',
            memory: '4096Mi',
          },
        },
        autoscaling: {
          // The chart only creates a PodDisruptionBudget when minReplicas > 1.
          minReplicas: 2,
          maxReplicas: 15,
        },
        podDisruptionBudget: {
          maxUnavailable: 1,
        },
        ...deploymentValues,
        service: {
          ...serviceValues,
          ports: [
            ingressPort('status-port', 15021), // istio default
            ingressPort('http2', 80, port80Protocol),
            ingressPort('https', 443),
          ].concat(ingressPorts),
        },
        ...infraKubernetesScheduling,
        // The httpLoadBalancing addon needs to be enabled to use backend service-based network load balancers.
        annotations: {
          'cloud.google.com/l4-rbs': 'enabled',
        },
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      replaceOnChanges: ['values.annotations'],
      deleteBeforeReplace: true,
      dependsOn: istioPolicies
        ? istioPolicies.apply(policies => {
            const base: pulumi.Resource[] = [ingressNs, istiod];
            return base.concat(policies);
          })
        : [ingressNs, istiod, ...extraDependencies],
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
  cometBftSvc: k8s.helm.v3.Release | undefined,
  withSeparateGcpGateway: boolean
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
            ...(withSeparateGcpGateway ? {} : { tls: { httpsRedirect: true } }),
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

  const servers = Array.from({ length: Math.min(numMigrations, 10) }, (_, i) => i).flatMap(
    migration => {
      const ret = Array.from({ length: numSVs }, (_, node) => node).map(node =>
        server(migration, node + 1)
      );
      if (!isMainNet) {
        // For non-mainnet clusters, include "node 0" for the sv runbook
        ret.unshift(server(migration, 0));
      }
      return ret;
    }
  );

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
      dependsOn: cometBftSvc ? [cometBftSvc] : [],
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
            {
              port: 80,
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
                {
                  port: 80,
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

function configureSequencerHighPerformanceGrpcDestinationRules(
  ingressNs: k8s.core.v1.Namespace
): Array<k8s.apiextensions.CustomResource> {
  return [
    ...(function* () {
      for (const migration of DecentralizedSynchronizerUpgradeConfig.runningMigrations()) {
        for (const sv of allSvsToDeployBasic) {
          yield configureSequencerHighPerformanceGrpcDestinationRule(
            ingressNs,
            sv.nodeName,
            migration.id
          );
        }
      }
    })(),
  ];
}

function configureSequencerHighPerformanceGrpcDestinationRule(
  ingressNs: k8s.core.v1.Namespace,
  nodeName: string,
  migrationId: number
): k8s.apiextensions.CustomResource {
  const sequencerName = `global-domain-${migrationId}-sequencer`;
  const ruleName = `${nodeName}-${sequencerName}-high-perf-grpc-rule`;
  return new k8s.apiextensions.CustomResource(ruleName, {
    apiVersion: 'networking.istio.io/v1beta1',
    kind: 'DestinationRule',
    metadata: {
      name: ruleName,
      namespace: ingressNs.metadata.name,
    },
    spec: {
      host: `${sequencerName}.${nodeName}.svc.cluster.local`,
      trafficPolicy: {
        loadBalancer: {
          simple: 'LEAST_REQUEST',
        },
        connectionPool: {
          http: {
            http1MaxPendingRequests: 20000,
            http2MaxRequests: 20000,
            maxConcurrentStreams: 20000,
            maxRequestsPerConnection: 0,
          },
          tcp: {
            maxConnections: 20000,
          },
        },
      },
    },
  });
}

// Istio proxies lots of client connections over relatively few connections. If one of the client connections gets stuck
// (e.g. because the client died) buffers will fill up and eventually istio will stop sending connection-level window updates
// to the sequencer and trigger netty flow control. This surfaces as requests that send back response headers but then nothing else until the client times out.
// To mitigate that we set the connection window size meaningfully higher than the stream window size. To fully mitigate it it likely needs to be
// connection window size >= stream window size * maxConcurrentStreams but that would increase istio memory significantly so for now the values are usually just high enough that it
// doesn't happen in practice but not enough to fully prevent it.
// We also enable http2 pings to ensure that connections get closed when clients go away and the istio buffers are cleared again.
// Note that we deliberately do not set stream idle timeouts as this doesn't work with long running requests.
// See https://github.com/DACH-NY/canton-network-internal/issues/4901#issuecomment-4461257908 for more details.
function configureSequencerFlowControl(
  ingressNs: k8s.core.v1.Namespace
): k8s.apiextensions.CustomResource {
  const http2ProtocolOptions = (config: z.infer<typeof flowControlConfigSchema>) => ({
    initial_stream_window_size: config.initialStreamWindowSize,
    initial_connection_window_size: config.initialConnectionWindowSize,
    connection_keepalive: {
      interval: '30s',
      timeout: '5s',
    },
  });
  // istio sidecar of upstream -> upstream (e.g. sequencer)
  const upstreamPatch = (portNumber: number, config: z.infer<typeof flowControlConfigSchema>) => ({
    applyTo: 'CLUSTER',
    match: {
      cluster: {
        portNumber,
        // Ideally we would just apply it everywhere. But doing it without this portNumber breaks http1 configs. In theory there is `auto_config` which should do the right thing but then it doesn't apply it at all anymore.
        // So for now we just apply it to the sequencer ports (public API and BFT P2P) which are the only externally exposed http2 servers so the only things where this really should matter in practice.
      },
    },
    patch: {
      operation: 'MERGE',
      value: {
        typed_extension_protocol_options: {
          'envoy.extensions.upstreams.http.v3.HttpProtocolOptions': {
            '@type': 'type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions',
            use_downstream_protocol_config: {
              http_protocol_options: {},
              http2_protocol_options: http2ProtocolOptions(config),
            },
          },
        },
      },
    },
  });
  return new k8s.apiextensions.CustomResource('sequencer-flow-control', {
    apiVersion: 'networking.istio.io/v1alpha3',
    kind: 'EnvoyFilter',
    metadata: {
      name: 'flow-control',
      namespace: ingressNs.metadata.name,
    },
    spec: {
      configPatches: [
        ...[
          {
            context: 'SIDECAR_INBOUND',
            // Shared by public and internal APIs.
            config: infraConfig.istio.flowControl.internal,
          },
          {
            context: 'GATEWAY',
            // Cover client/L7 load balancer -> ingress, not just ingress -> upstream.
            config: infraConfig.istio.flowControl.public,
          },
        ].map(({ context, config }) => ({
          applyTo: 'NETWORK_FILTER',
          match: {
            context,
            listener: {
              filterChain: {
                filter: {
                  name: 'envoy.filters.network.http_connection_manager',
                },
              },
            },
          },
          patch: {
            operation: 'MERGE',
            value: {
              typed_config: {
                '@type':
                  'type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager',
                http2_protocol_options: {
                  ...http2ProtocolOptions(config),
                  // The L7 load balancer forwards WebSockets as HTTP/2 extended CONNECT.
                  // Rejecting these headers closes the shared connection, failing unrelated requests.
                  ...(context === 'GATEWAY' ? { allow_connect: true } : {}),
                },
              },
            },
          },
        })),
        ...infraConfig.istio.flowControl.public.ports.map(p =>
          upstreamPatch(p, infraConfig.istio.flowControl.public)
        ),
        ...infraConfig.istio.flowControl.internal.ports.map(p =>
          upstreamPatch(p, infraConfig.istio.flowControl.internal)
        ),
      ],
    },
  });
}

function stripRateLimitHeaders(
  ingressNs: k8s.core.v1.Namespace,
  gwSvc: k8s.helm.v3.Release
): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(
    'strip-rate-limit-headers',
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'EnvoyFilter',
      metadata: {
        name: 'strip-rate-limit-headers',
        namespace: ingressNs.metadata.name,
      },
      spec: {
        workloadSelector: {
          labels: {
            istio: 'ingress',
          },
        },
        configPatches: [
          {
            applyTo: 'ROUTE_CONFIGURATION',
            match: {
              context: 'GATEWAY',
            },
            patch: {
              // repeated fields are appended, so this does not clobber anything istio sets
              operation: 'MERGE',
              value: {
                response_headers_to_remove: rateLimitResponseHeaders,
              },
            },
          },
        ],
      },
    },
    {
      dependsOn: [gwSvc],
    }
  );
}

export function configureIstio(
  ingressNs: ExactNamespace,
  ingressIp: pulumi.Output<string>,
  cometBftIngressIp: pulumi.Output<string>,
  expectGKEL7Gateway: boolean,
  extraIngressDependencies: pulumi.Resource[] = []
): ConfiguredIstio {
  const nsName = 'istio-system';
  const istioSystemNs = new k8s.core.v1.Namespace(nsName, {
    metadata: {
      name: nsName,
    },
  });
  const base = configureIstioBase(istioSystemNs, ingressNs.ns);
  const istiod = configureIstiod(ingressNs.ns, base);
  const gwSvc = configureInternalGatewayService(
    ingressNs.ns,
    expectGKEL7Gateway ? { viaGKEL7: true } : { viaGKEL7: false, ip: ingressIp },
    istiod,
    extraIngressDependencies
  );
  const cometBftSvc = DecentralizedSynchronizerUpgradeConfig.usesCometbft()
    ? configureCometBFTGatewayService(ingressNs.ns, cometBftIngressIp, istiod)
    : undefined;
  const gateways = configureGateway(ingressNs, gwSvc, cometBftSvc, expectGKEL7Gateway);
  const docsAndReleases = configureDocsAndReleases(true, gateways);
  const publicInfo = configurePublicInfo(ingressNs.ns);

  const publicTokenRegistry = infraConfig.istio.enablePublicTokenRegistry
    ? configurePublicTokenRegistry(ingressNs.ns)
    : [];

  const sequencerHighPerformanceGrpcRules = configureSequencerHighPerformanceGrpcDestinationRules(
    ingressNs.ns
  );
  const sequencerFlowControl = configureSequencerFlowControl(ingressNs.ns);
  installAppWhitelisting(ingressNs.ns);
  const rateLimitHeaderStripping = stripRateLimitHeaders(ingressNs.ns, gwSvc);
  return {
    allResources: [
      ...gateways,
      ...docsAndReleases,
      ...publicInfo,
      ...publicTokenRegistry,
      ...sequencerHighPerformanceGrpcRules,
      ...[sequencerFlowControl],
      ...[rateLimitHeaderStripping],
    ],
    httpServiceName: 'istio-ingress',
    istioResource: gwSvc,
  };
}
