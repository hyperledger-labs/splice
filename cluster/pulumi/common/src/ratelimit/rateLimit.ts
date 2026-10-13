// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { z } from 'zod';

import { clusterSubConfig } from '../config/config';
import {
  directIngressXffNumTrustedHops,
  gkeL7GatewayNumTrustedProxies,
  rateLimitedGrpcStatus,
  RateLimitEnvoyFilter,
  RateLimitProtocol,
} from './envoyRateLimiter';
import { ExternalRateLimit } from './rateLimitSchema';

const IngressTopologyConfigSchema = z.object({
  gkeGateway: z.object({ proxyForIstioHttp: z.boolean().default(false) }).prefault({}),
});

/**
 * The number of x-forwarded-for hops the sidecars must trust, which depends on whether the istio
 * ingress gateway is exposed directly by the NLB or fronted by the GKE L7 gateway (the Cloud Armor
 * prerequisite), as the latter adds proxies that each append an entry.
 */
export function sidecarXffNumTrustedHops(): number {
  return IngressTopologyConfigSchema.parse(clusterSubConfig('infra')).gkeGateway.proxyForIstioHttp
    ? gkeL7GatewayNumTrustedProxies
    : directIngressXffNumTrustedHops;
}

/**
 * Envoy answers a rate limited HTTP request with `429`, but a rate limited gRPC call with HTTP
 * `200` and the `RESOURCE_EXHAUSTED` gRPC status (see `rate_limited_as_resource_exhausted`), so
 * filtering on the HTTP status code alone would never match a gRPC rejection.
 */
export function rateLimitedRequestsExpression(protocol: RateLimitProtocol): string {
  return protocol === 'grpc'
    ? `response.grpc_status == ${rateLimitedGrpcStatus}`
    : 'response.code == 429';
}

/**
 * Makes the sidecar log every rate limited request, independently of whether access
 * logging is enabled cluster wide.
 */
function logRateLimitedRequests(
  namespace: string,
  app: string,
  protocol: RateLimitProtocol
): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(`${namespace}-${app}-rate-limit-access-log`, {
    apiVersion: 'telemetry.istio.io/v1',
    kind: 'Telemetry',
    metadata: {
      name: `${app}-rate-limit-access-log`,
      namespace,
    },
    spec: {
      selector: {
        matchLabels: {
          app,
        },
      },
      accessLogging: [
        {
          // the default envoy provider, which uses the mesh-wide accessLogFormat
          providers: [{ name: 'envoy' }],
          // Rate limited requests are recognizable in the log by
          // response_code_details=local_rate_limited and response_flags containing RL.
          filter: { expression: rateLimitedRequestsExpression(protocol) },
        },
      ],
    },
  });
}

export interface RateLimitOptions {
  protocol?: RateLimitProtocol;
}

export function installRateLimits(
  namespace: string,
  app: string,
  appPort: number,
  rateLimit: ExternalRateLimit,
  options: RateLimitOptions = {}
): void {
  const protocol = options.protocol || 'http';
  new RateLimitEnvoyFilter(`${app}-rate-limit`, {
    namespace: namespace,
    appLabel: app,
    inboundPort: appPort,
    globalLimits: rateLimit.globalLimits,
    globalPerIpLimits: rateLimit.globalPerIpLimits,
    rateLimits: rateLimit.rateLimits,
    protocol,
    xffNumTrustedHops: sidecarXffNumTrustedHops(),
  });
  logRateLimitedRequests(namespace, app, protocol);
}

// The sequencer's public API port (`grpc-cs-pub-api`), the only externally reachable sequencer
// port that carries client traffic. The admin (5009) and peer-to-peer (5010) ports must not be
// rate limited: they serve operator and inter-sequencer traffic, which would break under a limit
// sized for clients.
export const sequencerPublicApiPort = 5008;

export function installSequencerRateLimits(
  namespace: string,
  sequencerApp: string,
  rateLimit: ExternalRateLimit
): void {
  // the sequencer's public API is gRPC
  installRateLimits(namespace, sequencerApp, sequencerPublicApiPort, rateLimit, {
    protocol: 'grpc',
  });
}
