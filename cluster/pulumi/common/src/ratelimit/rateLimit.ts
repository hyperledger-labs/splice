// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Namespace } from '@pulumi/kubernetes/core/v1';

import { RateLimitEnvoyFilter } from './envoyRateLimiter';
import { ExternalRateLimit } from './rateLimitSchema';

export function installRateLimits(
  namespace: Namespace,
  app: string,
  appPort: number,
  rateLimit: ExternalRateLimit
): void {
  new RateLimitEnvoyFilter(`${app}-rate-limit`, {
    namespace: namespace.metadata.name,
    appLabel: app,
    inboundPort: appPort,
    globalLimits: rateLimit.globalLimits,
    rateLimits: rateLimit.rateLimits,
  });
}
