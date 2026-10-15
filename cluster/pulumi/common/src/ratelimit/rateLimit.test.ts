// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, jest, test } from '@jest/globals';

import { directIngressXffNumTrustedHops, gkeL7GatewayNumTrustedProxies } from './envoyRateLimiter';
import { rateLimitedRequestsExpression, sidecarXffNumTrustedHops } from './rateLimit';

// the real module reads the cluster yaml config at import time, which is not available in tests
const infraConfig: { gkeGateway?: { proxyForIstioHttp?: boolean } } = {};
jest.mock('../config/config', () => ({
  clusterSubConfig: (key: string) => (key === 'infra' ? infraConfig : {}),
}));

test('the access log filter matches the rejections of the configured protocol', () => {
  // a rate limited HTTP request is answered with 429
  expect(rateLimitedRequestsExpression('http')).toEqual('response.code == 429');
  // whereas a rate limited gRPC call (e.g. on the sequencer's public API) is answered with
  // HTTP 200 and the RESOURCE_EXHAUSTED (8) gRPC status, so filtering on the HTTP status code
  // would never log it
  expect(rateLimitedRequestsExpression('grpc')).toEqual('response.grpc_status == 8');
});

test('the trusted hop count follows the ingress topology', () => {
  // NLB with externalTrafficPolicy: Local, the gateway appends the client address itself
  infraConfig.gkeGateway = { proxyForIstioHttp: false };
  expect(sidecarXffNumTrustedHops()).toEqual(directIngressXffNumTrustedHops);

  // behind the GKE L7 gateway (required for Cloud Armor) the ALB and the istio gateway append
  // further entries, so trusting a single hop would key the per-IP buckets on the proxy-only
  // subnet address instead of the client
  infraConfig.gkeGateway = { proxyForIstioHttp: true };
  expect(sidecarXffNumTrustedHops()).toEqual(gkeL7GatewayNumTrustedProxies);
});
