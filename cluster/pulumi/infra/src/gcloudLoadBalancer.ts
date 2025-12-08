// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

// possible values and their meaning: https://docs.cloud.google.com/kubernetes-engine/docs/concepts/gateway-api#gatewayclass
const gcloudGatewayClass = 'gke-l7-global-external-managed';

interface L7GatewayConfig {
  gatewayName: string;
  ingressNs: ExactNamespace;
  serviceTarget: {
    name: string;
    port: number;
  };
  securityPolicyName?: pulumi.Input<string>;
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
        gatewayClassName: gcloudGatewayClass,
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
        ],
      },
    },
    opts
  );
}

/**
 * Creates a GCPBackendPolicy for Cloud Armor integration
 */
function createGCPBackendPolicy(
  config: L7GatewayConfig,
  opts?: pulumi.CustomResourceOptions
): k8s.apiextensions.CustomResource | undefined {
  if (!config.securityPolicyName) {
    return undefined;
  }

  const policyName = `${config.gatewayName}-backend-policy`;
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
          securityPolicy: config.securityPolicyName,
        },
        targetRef: {
          group: '',
          kind: 'Service',
          name: config.serviceTarget.name,
          namespace: config.ingressNs.ns.metadata.name,
        },
      },
    },
    opts
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
        targetRef: {
          group: '',
          kind: 'Service',
          name: config.serviceTarget.name,
          namespace: config.ingressNs.ns.metadata.name,
        },
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
                name: config.serviceTarget.name,
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
  backendPolicy?: k8s.apiextensions.CustomResource;
  healthCheckPolicy: k8s.apiextensions.CustomResource;
  httpRoute: k8s.apiextensions.CustomResource;
} {
  const gateway = createL7Gateway(config, opts);

  const backendPolicy = createGCPBackendPolicy(config, {
    ...opts,
    dependsOn: [gateway],
  });

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
