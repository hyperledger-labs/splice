// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RateLimitEnvoyFilter } from './envoyRateLimiter';
import { ExternalRateLimit } from './rateLimitSchema';

export function installRateLimits(
  namespace: string,
  app: string,
  appPort: number,
  rateLimit: ExternalRateLimit
): void {
  new RateLimitEnvoyFilter(`${app}-rate-limit`, {
    namespace: namespace,
    appLabel: app,
    inboundPort: appPort,
    globalLimits: rateLimit.globalLimits,
    rateLimits: rateLimit.rateLimits,
  });
}
