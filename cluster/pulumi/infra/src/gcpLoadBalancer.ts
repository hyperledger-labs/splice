// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { CLUSTER_BASENAME, ExactNamespace } from '@canton-network/splice-pulumi-common';
import { CloudArmorLoggingConfig } from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import { local } from '@pulumi/command';

import { CloudArmorPolicy } from './cloudArmor';

/*
Any cluster that uses this must first run

   cncluster cluster_enable_gke_l7_alb

It's safe to run just to be sure as well; see its comments for errors
likely if you haven't run it yet.
 */

/*
If you turn off Cloud Armor and see the error
    kubernetes:helm.sh/v3:Release  istio-ingress         **updating failed**     [diff: ~values]
you may need to force the gateway destroy first:
    cncluster pulumi infra up --target 'urn:pulumi:infra.'$GCP_CLUSTER_BASENAME'::infra::kubernetes:gateway.networking.k8s.io/v1:Gateway::cn-gke-l7-gateway' --target-dependents
then normal up/apply should work
 */

// possible values and their meaning: https://docs.cloud.google.com/kubernetes-engine/docs/concepts/gateway-api#gatewayclass
// global vs regional:
//   - the ingressAddress must match
//   - the CloudArmorPolicy must match
//   - the SSL policy must match
const gcpGatewayClass = 'gke-l7-regional-external-managed';

interface L7GatewayConfig {
  gatewayName: string;
  ingressNs: ExactNamespace;
  // the pre-reserved GCP ingress IP. The Gateway will reference this
  // as a NamedAddress
  ingressAddress: gcp.compute.Address;
  // should be the name of the Service (k8s resource) that is the backend target;
  // see backendTargetRef for mapping from gateway name
  backendServiceName: pulumi.Input<string>;
  serviceTarget: {
    port: number;
  };
  // the istio gateway service that must be updated before creating this gateway
  istioResource: pulumi.Resource;
  // the Cloud Armor policy to attach
  securityPolicy?: CloudArmorPolicy;
  // LB backend service request logging, controlled via the cloudArmor.logging config
  backendLogging?: CloudArmorLoggingConfig;
  // if provided, an HTTPS listener will be created on port 443 that
  // terminates TLS using this secret
  tlsSecretName?: pulumi.Input<string>;
  // hostnames that must not be evaluated by Cloud Armor. They are routed to a
  // second k8s Service in front of the same istio ingress pods, which the GKE
  // controller turns into its own GCP backend service, and only that backend
  // service is left without a security policy.
  cloudArmorExemptHostnames?: string[];
}

const httpListenerName = 'listen-http';
const httpsListenerName = 'listen-https';

// For HTTP/2 backends the GCP backend service timeout acts as a max stream duration,
// not an idle timeout, so the default of 30s tears down every long-lived gRPC stream
// (e.g. the sequencer public API subscriptions) mid-flight. Those show up as
// `proxyStatus: error="connection_terminated"` in the load balancer logs.
const BACKEND_TIMEOUT_SECONDS = 3600;

// Stop sending new requests to a removed backend endpoint and let the in-flight ones
// finish instead of cutting them. Must stay consistent with the preStop drain on the
// istio-ingress pods, see PRE_STOP_DRAIN_SECONDS in istio.ts.
const BACKEND_DRAINING_TIMEOUT_SECONDS = 60;

// enforced by the HTTPRoute CRD: spec.hostnames must have at most 16 items
const MAX_HOSTNAMES_PER_HTTP_ROUTE = 16;

/**
 * Creates a GKE L7 Gateway
 */
function createL7Gateway(config: L7GatewayConfig): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(
    config.gatewayName,
    {
      apiVersion: 'gateway.networking.k8s.io/v1',
      // using a Gateway requires that --gateway-api=standard is set for the GKE cluster
      kind: 'Gateway',
      metadata: {
        name: config.gatewayName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        gatewayClassName: gcpGatewayClass,
        listeners: [
          {
            name: httpListenerName,
            protocol: 'HTTP',
            port: 80,
            allowedRoutes: {
              kinds: [
                {
                  kind: 'HTTPRoute',
                },
              ],
            },
          },
          // if a TLS secret is provided, terminate TLS at this layer
          ...(config.tlsSecretName
            ? [
                {
                  name: httpsListenerName,
                  protocol: 'HTTPS',
                  port: 443,
                  allowedRoutes: {
                    kinds: [
                      {
                        kind: 'HTTPRoute',
                      },
                    ],
                  },
                  tls: {
                    mode: 'Terminate',
                    certificateRefs: [
                      {
                        // see https://gateway-api.sigs.k8s.io/reference/spec/#secretobjectreference
                        name: config.tlsSecretName,
                        group: '',
                        kind: 'Secret',
                      },
                    ],
                  },
                },
              ]
            : []),
        ],
        // per gateway Error GWCER106: unsupported address type "IPAddress",
        // only "NamedAddress" is supported
        addresses: [{ type: 'NamedAddress', value: config.ingressAddress.name }],
      },
    },
    { dependsOn: [config.istioResource] }
  );
}

type BackendTargetRef = {
  group: string;
  kind: string;
  name: pulumi.Input<string>;
  namespace: pulumi.Input<string>;
};

function backendTargetRef(
  config: L7GatewayConfig,
  // must be the name of the Service set up by the gateway
  // *that is the backend of the L7 ALB gateway for which this is configured*.
  // For a classic istio gateway this is the same as the 'name' set on the gateway helm chart;
  // for a k8s istio gateway this is <gateway-name>-istio.
  // Can be identified by the apiVersion of the Gateway k8s resource
  serviceName: pulumi.Input<string> = config.backendServiceName
): BackendTargetRef {
  return {
    group: '',
    kind: 'Service',
    name: serviceName,
    namespace: config.ingressNs.ns.metadata.name,
  };
}

/**
 * Creates a GCPBackendPolicy for backend request logging and, if a policy is given,
 * Cloud Armor integration. Omitting the policy leaves the backend service without a
 * security policy, so requests routed to it are not evaluated by Cloud Armor.
 */
function attachBackendPolicy(
  policy: CloudArmorPolicy | undefined,
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  policyName: string,
  serviceName: pulumi.Input<string> = config.backendServiceName
): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(
    policyName,
    {
      apiVersion: 'networking.gke.io/v1',
      kind: 'GCPBackendPolicy',
      metadata: {
        name: policyName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        default: {
          timeoutSec: BACKEND_TIMEOUT_SECONDS,
          connectionDraining: {
            drainingTimeoutSec: BACKEND_DRAINING_TIMEOUT_SECONDS,
          },
          // backend service request logging must be enabled for Cloud Armor
          // rule decisions to show up in Cloud Logging. Without a policy there are no
          // rejections, so nothing to log if only those are wanted.
          ...(config.backendLogging?.enabled &&
          !(config.backendLogging.excludeAcceptedRequests && !policy)
            ? {
                logging: {
                  enabled: true,
                  // rate out of 1'000'000, converted from the 0.0-1.0 config fraction
                  sampleRate: Math.round(config.backendLogging.sampleRate * 1000000),
                },
              }
            : {}),
          // if global vs regional is mismatched you'll see
          // SetSecurityPolicy: Invalid value for field 'resource': '{  "securityPolicy": "https://www.googleapis.com/compute/beta/projects/da-cn-scratchnet/regions/us-c...'. The given security policy does not exist
          ...(policy
            ? {
                securityPolicy: policy
                  ? policy.name.apply(name => {
                      console.assert(
                        !name.includes('/'),
                        `${name} should be just the name, not a full resource path`
                      );
                      return name;
                    })
                  : '',
              }
            : {}),
        },
        targetRef: backendTargetRef(config, serviceName),
      },
    },
    { parent: gateway, dependsOn: policy ? [policy] : [] }
  );
}

/**
 * Creates a HealthCheckPolicy for the L7 Gateway.
 * We do this explicitly because the default path is '/'
 */
function createHealthCheckPolicy(
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  policyName: string = `${config.gatewayName}-healthcheck`,
  serviceName: pulumi.Input<string> = config.backendServiceName
): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(
    policyName,
    {
      apiVersion: 'networking.gke.io/v1',
      kind: 'HealthCheckPolicy',
      metadata: {
        name: policyName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        default: {
          config: {
            type: 'HTTP',
            httpHealthCheck: {
              // Istio's default status endpoint
              port: 15021,
              requestPath: '/healthz/ready',
            },
          },
        },
        targetRef: backendTargetRef(config, serviceName),
      },
    },
    { parent: gateway }
  );
}

/**
 * The parentRef for routes serving actual traffic: the https listener when TLS is
 * terminated at the gateway, the gateway itself otherwise.
 */
function mainRouteParentRef(config: L7GatewayConfig) {
  return {
    name: config.gatewayName,
    namespace: config.ingressNs.ns.metadata.name,
    ...(config.tlsSecretName ? { sectionName: httpsListenerName } : {}),
  };
}

/**
 * Routes the Cloud Armor exempt hostnames to their own k8s Service in front of the same
 * istio ingress pods.
 *
 * The GKE Gateway controller creates one GCP backend service per (Service, port) pair
 * referenced by a route, and Cloud Armor is attached per backend service via
 * GCPBackendPolicy. Adding a second Service with the same pod selector therefore gives
 * these hostnames a backend service that has no security policy attached, without
 * needing a separate IP, DNS records, certificate SANs or istio ingress deployment.
 *
 * Gateway API precedence puts routes with matching hostnames ahead of the hostname-less
 * catch-all route, so these hosts are served by this route rather than the main one.
 */
function createCloudArmorExemptBackend(
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  hostnames: string[]
): void {
  const name = `${config.gatewayName}-no-cloud-armor`;
  const service = new k8s.core.v1.Service(
    name,
    {
      metadata: {
        name,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        type: 'ClusterIP',
        selector: { app: 'istio-ingress' },
        ports: [
          {
            name: 'http2',
            port: config.serviceTarget.port,
            targetPort: config.serviceTarget.port,
            // force HTTP/2 (h2c) between the L7 gateway and istio-ingress, as the
            // exempt traffic is gRPC
            appProtocol: 'kubernetes.io/h2c',
          },
        ],
      },
    },
    { parent: gateway, dependsOn: [config.istioResource] }
  );

  // the HTTPRoute CRD caps spec.hostnames at 16 entries, so spread them over as many
  // routes as needed; they all point at the same backend service
  _.chunk(hostnames, MAX_HOSTNAMES_PER_HTTP_ROUTE).forEach((hostnameChunk, i) => {
    // keep the first route's name stable, so that clusters that fit into a single
    // route are not forced to replace it
    const routeName = i === 0 ? `${name}-route` : `${name}-route-${i}`;
    new k8s.apiextensions.CustomResource(
      routeName,
      {
        apiVersion: 'gateway.networking.k8s.io/v1',
        kind: 'HTTPRoute',
        metadata: {
          name: routeName,
          namespace: config.ingressNs.ns.metadata.name,
        },
        spec: {
          parentRefs: [mainRouteParentRef(config)],
          hostnames: hostnameChunk,
          rules: [
            {
              backendRefs: [
                {
                  name: service.metadata.name,
                  namespace: config.ingressNs.ns.metadata.name,
                  port: config.serviceTarget.port,
                },
              ],
            },
          ],
        },
      },
      { parent: gateway, dependsOn: [service] }
    );
  });

  createHealthCheckPolicy(config, gateway, `${name}-healthcheck`, service.metadata.name);
  // deliberately no security policy: that is the whole point of this backend
  attachBackendPolicy(undefined, config, gateway, `${name}-backend-policy`, service.metadata.name);
}

/**
 * Creates HTTPRoutes for the L7 Gateway
 */
function createHTTPRoute(config: L7GatewayConfig, gateway: k8s.apiextensions.CustomResource): void {
  const routeName = `${config.gatewayName}-http-route`;

  const parentRef = {
    name: config.gatewayName,
    namespace: config.ingressNs.ns.metadata.name,
  };

  const routeOpts = { parent: gateway };

  // if we terminate TLS, make an extra redirect route and limit the main route
  // to the https listener
  if (config.tlsSecretName) {
    // Redirect route: parentRef points to the http listener (sectionName 'http')
    const redirectRouteName = `${config.gatewayName}-http-redirect`;
    new k8s.apiextensions.CustomResource(
      redirectRouteName,
      {
        apiVersion: 'gateway.networking.k8s.io/v1',
        kind: 'HTTPRoute',
        metadata: {
          name: redirectRouteName,
          namespace: config.ingressNs.ns.metadata.name,
        },
        spec: {
          parentRefs: [
            {
              ...parentRef,
              sectionName: httpListenerName,
            },
          ],
          rules: [
            {
              // no matches are specified, so the default is a prefix path match on `/`
              // redirect all requests to https on port 443
              filters: [
                {
                  type: 'RequestRedirect',
                  requestRedirect: {
                    // https://gateway-api.sigs.k8s.io/reference/spec/#httprequestredirectfilter
                    scheme: 'https',
                    // `port` is unsupported in GKE: https://docs.cloud.google.com/kubernetes-engine/docs/how-to/gatewayclass-capabilities#spec-rules-filters
                    // but `scheme` is, and the standard port for the scheme is inferred
                    statusCode: 301,
                  },
                },
              ],
            },
          ],
        },
      },
      routeOpts
    );
  }

  // Main route, limited to https listener if enabled
  new k8s.apiextensions.CustomResource(
    routeName,
    {
      apiVersion: 'gateway.networking.k8s.io/v1',
      kind: 'HTTPRoute',
      metadata: {
        name: routeName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        parentRefs: [mainRouteParentRef(config)],
        rules: [
          {
            // default match, prefix path `/`
            backendRefs: [
              {
                name: config.backendServiceName,
                namespace: config.ingressNs.ns.metadata.name,
                port: config.serviceTarget.port,
              },
            ],
          },
        ],
      },
    },
    routeOpts
  );
}

/**
 * Creates a GCP SSL Policy that enforces TLS 1.2+
 * https://www.pulumi.com/registry/packages/gcp/api-docs/compute/sslpolicy/
 */
function createSSLPolicy(config: L7GatewayConfig): gcp.compute.RegionSslPolicy {
  const policyName = `${config.gatewayName}-${CLUSTER_BASENAME}-ssl-policy`;
  return new gcp.compute.RegionSslPolicy(policyName, {
    name: policyName,
    description: `SSL Policy for ${config.gatewayName} enforcing TLS 1.2+`,
    // use gcp.compute.SSLPolicy for global
    region: config.ingressAddress.region,
    // https://docs.cloud.google.com/load-balancing/docs/ssl-policies-concepts#defining_an_ssl_policy
    profile: 'RESTRICTED',
    minTlsVersion: 'TLS_1_2',
  });
}

/**
 * Create a GCPGatewayPolicy to configure TLS settings
 * References a GCP SSL Policy that disables TLS 1.0 and 1.1
 * https://cloud.google.com/kubernetes-engine/docs/how-to/configure-gateway-resources#configure_ssl_policies
 */
function attachTLSPolicyToGateway(
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  sslPolicy: gcp.compute.RegionSslPolicy
): k8s.apiextensions.CustomResource {
  const policyName = `${config.gatewayName}-ssl-policy-attachment`;
  return new k8s.apiextensions.CustomResource(
    policyName,
    {
      apiVersion: 'networking.gke.io/v1',
      kind: 'GCPGatewayPolicy',
      metadata: {
        name: policyName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        default: {
          sslPolicy: sslPolicy.name,
        },
        targetRef: {
          group: 'gateway.networking.k8s.io',
          kind: 'Gateway',
          name: config.gatewayName,
          namespace: config.ingressNs.ns.metadata.name,
        },
      },
    },
    {
      parent: gateway,
      dependsOn: [sslPolicy],
      replaceOnChanges: ['spec.default.sslPolicy'],
      deleteBeforeReplace: true,
    }
  );
}

/**
 * Configures a complete GKE L7 Gateway with optional Cloud Armor integration
 */
export function configureGKEL7Gateway(config: L7GatewayConfig): {
  gateway: k8s.apiextensions.CustomResource;
} {
  const gateway = createL7Gateway(config);

  // always created a backend policy to detach Cloud Armor from the backend service
  const cloudArmorLink = attachBackendPolicy(
    config.securityPolicy,
    config,
    gateway,
    `${config.gatewayName}-cloud-armor-link`
  );

  createHealthCheckPolicy(config, gateway);

  if (config.cloudArmorExemptHostnames?.length) {
    createCloudArmorExemptBackend(config, gateway, config.cloudArmorExemptHostnames);
  }

  const sslPolicy = createSSLPolicy(config);
  attachTLSPolicyToGateway(config, gateway, sslPolicy);

  createHTTPRoute(config, gateway);

  return { gateway };
}
