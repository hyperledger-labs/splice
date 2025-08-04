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
