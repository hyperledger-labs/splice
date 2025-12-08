// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';
import * as gcp from "@pulumi/gcp";

// possible values and their meaning: https://docs.cloud.google.com/kubernetes-engine/docs/concepts/gateway-api#gatewayclass
const gcloudGatewayClass = 'gke-l7-global-external-managed';

interface L7GatewayConfig {
  gatewayName: string;
  ingressNs: ExactNamespace;
  serviceTarget: {
    name: string;
    port: number;
  };
  securityPolicy: gcp.compute.SecurityPolicy;
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
        targetRef: {
          group: '',
          kind: 'Service',
          // TODO (#2723) must be the name of the Service set up by the gateway
          // *that is the backend of the L7 ALB gateway for which this is configured*.
          // For a classic istio gateway this is the same (?) as the gateway name;
          // for a k8s istio gateway this is <gateway-name>-istio.
          // Can be identified by the apiVersion of the Gateway k8s resource
          name: config.serviceTarget.name,
          namespace: config.ingressNs.ns.metadata.name,
        },
      },
    },
    {...opts, parent: config.securityPolicy, dependsOn: [gateway, config.securityPolicy] }
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
  backendPolicy: k8s.apiextensions.CustomResource;
  healthCheckPolicy: k8s.apiextensions.CustomResource;
  httpRoute: k8s.apiextensions.CustomResource;
} {
  const gateway = createL7Gateway(config, opts);

  const backendPolicy = createGCPBackendPolicy(config, gateway, {
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
