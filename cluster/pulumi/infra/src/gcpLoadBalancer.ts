// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

/*
If you see the error
  no matches for kind "Gateway" in version "gateway.networking.k8s.io/v1"
you need to add the CRD; on a cncluster-controlled dev cluster directory you can do
  gcloud container clusters update "cn-${GCP_CLUSTER_BASENAME}net" --gateway-api=standard
 */

// possible values and their meaning: https://docs.cloud.google.com/kubernetes-engine/docs/concepts/gateway-api#gatewayclass
const gcpGatewayClass = 'gke-l7-global-external-managed';

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
  // the Cloud Armor policy to attach
  securityPolicy: gcp.compute.SecurityPolicy;
  // if provided, an HTTPS listener will be created on port 443 that
  // terminates TLS using this secret
  tlsSecretName?: pulumi.Input<string>;
}

const httpListenerName = 'listen-http';
const httpsListenerName = 'listen-https';

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
          // TODO (#2723) compare to format in cni experiment
          securityPolicy: config.securityPolicy.selfLink,
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
              // TODO (#2723) confirm Istio's default status port answers readyz
              port: 15021,
              requestPath: '/readyz',
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
 * Creates HTTPRoutes for the L7 Gateway
 */
function createHTTPRoute(
  config: L7GatewayConfig,
  gateway: k8s.apiextensions.CustomResource,
  opts?: pulumi.CustomResourceOptions
): void {
  const routeName = `${config.gatewayName}-http-route`;

  const parentRef = {
    name: config.gatewayName,
    namespace: config.ingressNs.ns.metadata.name,
  };

  const routeOpts = { ...opts, dependsOn: [gateway] };

  let sectionExtension: { sectionName: typeof httpsListenerName } | Record<string, never> = {};

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

    sectionExtension = { sectionName: httpsListenerName };
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
        parentRefs: [
          {
            ...parentRef,
            ...sectionExtension,
          },
        ],
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
 * Configures a complete GKE L7 Gateway with optional Cloud Armor integration
 */
export function configureGKEL7Gateway(
  config: L7GatewayConfig,
  opts?: pulumi.ComponentResourceOptions
): {
  gateway: k8s.apiextensions.CustomResource;
} {
  const gateway = createL7Gateway(config, opts);

  createGCPBackendPolicy(config, gateway, {
    ...opts,
    dependsOn: [gateway],
  });

  createHealthCheckPolicy(config, {
    ...opts,
    dependsOn: [gateway],
  });

  createHTTPRoute(config, gateway, opts);

  return { gateway };
}
