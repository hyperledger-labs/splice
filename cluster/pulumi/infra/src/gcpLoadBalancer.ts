// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

// possible values and their meaning: https://docs.cloud.google.com/kubernetes-engine/docs/concepts/gateway-api#gatewayclass
const gcpGatewayClass = 'gke-l7-regional-external-managed';

interface L7GatewayConfig {
  gatewayName: string;
  ingressNs: ExactNamespace;
  // should be the name of the Service (k8s resource) that is the backend target;
  // see backendTargetRef for mapping from gateway name
  backendServiceName: pulumi.Input<string>;
  serviceTarget: {
    port: number;
  };
  // if omitted, no GCPBackendPolicy will be created
  readonly securityPolicy?: gcp.compute.SecurityPolicy;
  // if provided, an HTTPS listener will be created on port 443 that
  // terminates TLS using this secret
  tlsSecretName?: pulumi.Input<string>;
}

/**
 * Creates a GKE L7 Gateway
 */
function createL7Gateway(
  config: L7GatewayConfig,
  opts?: pulumi.CustomResourceOptions
): k8s.apiextensions.CustomResource {
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
            name: 'http',
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
                  name: 'https',
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
      },
    },
    opts
  );
}

type BackendTargetRef = {
  group: string;
  kind: string;
  name: pulumi.Input<string>;
  namespace: pulumi.Input<string>;
};

function backendTargetRef(config: L7GatewayConfig): BackendTargetRef {
  return {
    group: '',
    kind: 'Service',
    // must be the name of the Service set up by the gateway
    // *that is the backend of the L7 ALB gateway for which this is configured*.
    // For a classic istio gateway this is the same as the 'name' set on the gateway helm chart;
    // for a k8s istio gateway this is <gateway-name>-istio.
    // Can be identified by the apiVersion of the Gateway k8s resource
    name: config.backendServiceName,
    namespace: config.ingressNs.ns.metadata.name,
  };
}

/**
 * Creates a GCPBackendPolicy for Cloud Armor integration
 */
function createGCPBackendPolicy(
  config: L7GatewayConfig & Required<Pick<L7GatewayConfig, 'securityPolicy'>>,
  gateway: k8s.apiextensions.CustomResource,
  opts?: pulumi.CustomResourceOptions
): k8s.apiextensions.CustomResource {
  const policyName = `${config.gatewayName}-cloud-armor-link`;
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
          securityPolicy: config.securityPolicy.name,
        },
        targetRef: backendTargetRef(config),
      },
    },
    { ...opts, parent: config.securityPolicy, dependsOn: [gateway, config.securityPolicy] }
  );
}

/**
 * Creates a HealthCheckPolicy for the L7 Gateway.
 * We do this explicitly because the default path is '/'
 */
function createHealthCheckPolicy(
  config: L7GatewayConfig,
  opts?: pulumi.CustomResourceOptions
): k8s.apiextensions.CustomResource {
  const policyName = `${config.gatewayName}-healthcheck`;
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
              port: config.serviceTarget.port,
              requestPath: '/healthz',
            },
          },
        },
        targetRef: backendTargetRef(config),
      },
    },
    opts
  );
}

/**
 * Creates an HTTPRoute for the L7 Gateway
 */
function createHTTPRoute(
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  opts?: pulumi.CustomResourceOptions
): k8s.apiextensions.CustomResource {
  const routeName = `${config.gatewayName}-http-route`;
  return new k8s.apiextensions.CustomResource(
    routeName,
    {
      apiVersion: 'gateway.networking.k8s.io/v1',
      kind: 'HTTPRoute',
      metadata: {
        name: routeName,
        namespace: config.ingressNs.ns.metadata.name,
      },
      spec: {
        parentRefs: [
          {
            name: config.gatewayName,
            namespace: config.ingressNs.ns.metadata.name,
          },
        ],
        rules: [
          {
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
    { ...opts, dependsOn: [gateway] }
  );
}

/**
 * Configures a complete GKE L7 Gateway with optional Cloud Armor integration
 */
export function configureGKEL7Gateway(
  config: L7GatewayConfig,
  opts?: pulumi.ComponentResourceOptions
): {
  gateway: k8s.apiextensions.CustomResource;
  backendPolicy: k8s.apiextensions.CustomResource;
  healthCheckPolicy: k8s.apiextensions.CustomResource;
  httpRoute: k8s.apiextensions.CustomResource;
} {
  const gateway = createL7Gateway(config, opts);

  const backendPolicy = config.securityPolicy
    ? createGCPBackendPolicy(config, gateway, {
        ...opts,
        dependsOn: [gateway],
      })
    : undefined;

  const healthCheckPolicy = createHealthCheckPolicy(config, {
    ...opts,
    dependsOn: [gateway],
  });

  const httpRoute = createHTTPRoute(config, gateway, opts);

  return {
    gateway,
    backendPolicy,
    healthCheckPolicy,
    httpRoute,
  };
}
