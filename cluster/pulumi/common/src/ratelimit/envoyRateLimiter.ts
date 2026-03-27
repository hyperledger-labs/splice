// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { parseScanYamlEndpoints } from '../config/scanEndpoints';

interface Limits {
  maxTokens: number;
  tokensPerFill: number;
  fillInterval: string;
}

interface MatchedLimits extends Limits {
  type: 'limited';
  clientIp: boolean;
}

interface Banned {
  type: 'banned';
}

interface Unlimited {
  type: 'unlimited';
}

type RateLimitConfig = MatchedLimits | Banned | Unlimited;

export interface PathPrefixInfo {
  pathPrefix: string;
  isBanned: boolean;
}

interface LocalLimits<L> {
  [pathPrefix: string]: LocalLimit<L>;
}

type LocalLimit<L> = {
  name: string;
} & L;

// This is arbitrary, but must not match any limit `name` used for an EnvoyFilter
// above. All existing manual YAML entries use 'client_ip' so this is the nicest
// migration away from always specifying that.
const clientIpEntryKey = 'client_ip';

interface RateLimitEnvoyFilterArgs extends PerEndpointLimits {
  namespace: string;

  appLabel: pulumi.Input<string>;

  inboundPort: pulumi.Input<number>;

  /**
   * Used when no descriptors match the request.
   * */
  globalLimits: Limits;
}

export interface PerEndpointLimits {
  // all the rate limits must be respected, there's an AND relationship between them
  rateLimits?: LocalLimits<RateLimitConfig>;
}

export function extractPathPrefixes(
  rateLimits?: PerEndpointLimits['rateLimits']
): PathPrefixInfo[] {
  if (!rateLimits) {
    return [];
  }

  return Object.entries(rateLimits)
    .map(([pathPrefix, rl]) => {
      const isBanned = rl.type === 'banned';
      return { pathPrefix, isBanned };
    })
    .filter(info => info.pathPrefix.startsWith('/api/scan'));
}

function validateEndpointCoverage(
  scanEndpoints: string[],
  configuredScanPrefixes: string[]
): { missing: string[]; orphaned: string[] } {
  // Check for missing prefixes
  const missing = scanEndpoints.filter(
    endpoint => !configuredScanPrefixes.some(prefix => endpoint.startsWith(prefix))
  );

  // Check for orphaned prefixes
  const orphaned = configuredScanPrefixes.filter(
    prefix => !scanEndpoints.some(endpoint => endpoint.startsWith(prefix))
  );

  return { missing, orphaned };
}

function validateEffectiveRateLimits(
  args: RateLimitEnvoyFilterArgs
): LocalLimits<MatchedLimits> | undefined {
  const collidingPathNames = Object.entries(args.rateLimits || {})
    .filter(([, rl]) => rl.name === clientIpEntryKey)
    .map(([path]) => path);
  if (collidingPathNames.length > 0) {
    throw new Error(
      `${collidingPathNames.join(', ')} use reserved name ${clientIpEntryKey}; choose a different name`
    );
  }

  // Validate scan.yaml endpoint coverage
  const scanEndpoints = parseScanYamlEndpoints();

  const configuredScanPrefixes = Object.keys(args.rateLimits || {}).filter(pathPrefix =>
    pathPrefix.startsWith('/api/scan')
  );

  const { missing, orphaned } = validateEndpointCoverage(scanEndpoints, configuredScanPrefixes);

  if (missing.length > 0 || orphaned.length > 0) {
    const errorParts: string[] = ['Rate limit configuration errors:'];
    if (missing.length > 0) {
      errorParts.push(
        `- Missing rate limit prefixes for scan.yaml endpoints: ${missing.join(', ')}`
      );
    }
    if (orphaned.length > 0) {
      errorParts.push(
        `- Orphaned rate limit prefixes not matching any scan.yaml endpoint: ${orphaned.join(', ')}`
      );
    }
    throw new Error(errorParts.join('\n'));
  }

  // Filter out banned and unlimited entries
  return Object.fromEntries(
    Object.entries(args.rateLimits || {}).filter(
      (ent): ent is [string, LocalLimit<MatchedLimits>] => {
        // TODO (#4201): in banned case, implement actual banning with special short-circuit for whitelisted IPs
        // Currently skipping banned endpoints instead of setting 0/0 limits
        // in unlimited case, we fall back to globalRateLimit so don't need a rule
        const [, rl] = ent;
        return rl.type === 'limited';
      }
    )
  );
}

export class RateLimitEnvoyFilter extends pulumi.ComponentResource {
  public readonly envoyFilter: k8s.apiextensions.CustomResource;

  constructor(
    name: string,
    args: RateLimitEnvoyFilterArgs,
    opts?: pulumi.ComponentResourceOptions
  ) {
    super('splice:RateLimit', `splice-${args.namespace}-${name}`, args, opts);
    const effectiveRateLimits = validateEffectiveRateLimits(args);

    const rateLimitActions: unknown[] =
      Object.entries(effectiveRateLimits || {}).map(([pathPrefix, rateLimit]) => {
        return {
          actions: [
            {
              header_value_match: {
                descriptor_value: rateLimit.name,
                expect_match: true,
                headers: [
                  {
                    name: ':path',
                    string_match: {
                      prefix: pathPrefix,
                      ignore_case: true,
                    },
                  },
                ],
              },
            },
            ...(rateLimit.clientIp
              ? [
                  {
                    request_headers: {
                      descriptor_key: 'client_ip',
                      header_name: 'x-forwarded-for',
                    },
                  },
                ]
              : []),
          ],
        };
      }) || [];

    const enableEnvoyRateLimitMetricsAnnotation = `
proxyStatsMatcher:
  inclusionRegexps:
  - ".*http_local_rate_limit.*"
`.trim();

    this.envoyFilter = new k8s.apiextensions.CustomResource(
      `${args.namespace}-${name}`,
      {
        apiVersion: 'networking.istio.io/v1alpha3',
        kind: 'EnvoyFilter',
        metadata: {
          name: name,
          namespace: args.namespace,
          annotations: {
            'proxy.istio.io/config': enableEnvoyRateLimitMetricsAnnotation,
          },
        },
        spec: {
          workloadSelector: {
            labels: {
              app: args.appLabel,
            },
          },
          configPatches: [
            // Patch 1: Add the rate limit filter to the HTTP filter chain.
            {
              applyTo: 'HTTP_FILTER',
              match: {
                context: 'SIDECAR_INBOUND',
                listener: {
                  filterChain: {
                    filter: {
                      name: 'envoy.filters.network.http_connection_manager',
                    },
                  },
                },
              },
              patch: {
                operation: 'INSERT_BEFORE',
                value: {
                  name: 'envoy.filters.http.local_ratelimit',
                  typed_config: {
                    '@type': 'type.googleapis.com/udpa.type.v1.TypedStruct',
                    type_url:
                      'type.googleapis.com/envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit',
                    value: {
                      stat_prefix: 'http_local_rate_limiter',
                    },
                  },
                },
              },
            },
            // Patch 2: Configure the rate limiting rules on the HTTP route.
            {
              applyTo: 'HTTP_ROUTE',
              match: {
                context: 'SIDECAR_INBOUND',
                routeConfiguration: {
                  vhost: {
                    name: pulumi.interpolate`inbound|http|${args.inboundPort}`,
                    route: { action: 'ANY' },
                  },
                },
              },
              patch: {
                operation: 'MERGE',
                value: {
                  route: {
                    rate_limits: rateLimitActions,
                  },
                  typed_per_filter_config: {
                    'envoy.filters.http.local_ratelimit': {
                      '@type':
                        'type.googleapis.com/envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit',
                      stat_prefix: 'http_local_rate_limiter',
                      token_bucket: {
                        max_tokens: args.globalLimits.maxTokens,
                        tokens_per_fill: args.globalLimits.tokensPerFill,
                        fill_interval: args.globalLimits.fillInterval,
                      },
                      filter_enabled: {
                        runtime_key: 'local_rate_limit_enabled',
                        default_value: {
                          numerator: 100,
                          denominator: 'HUNDRED',
                        },
                      },
                      filter_enforced: {
                        runtime_key: 'local_rate_limit_enforced',
                        default_value: {
                          numerator: 100,
                          denominator: 'HUNDRED',
                        },
                      },
                      response_headers_to_add: [
                        {
                          append_action: 'OVERWRITE_IF_EXISTS_OR_ADD',
                          header: {
                            key: 'x-local-rate-limit',
                            value: 'true',
                          },
                        },
                      ],
                      // simplified descriptors by combining with actions and requiring all the tokens of an action to be set
                      // a descriptor in practice is a subset of tags from a rate limit
                      // but important to note that for each rate limit only one descriptor can match, if multiple descriptors match, the first one is used
                      descriptors: Object.values(effectiveRateLimits || {}).map(rateLimit => {
                        return {
                          entries: [
                            {
                              key: 'header_match',
                              value: rateLimit.name,
                            },
                            ...(rateLimit.clientIp ? [{ key: clientIpEntryKey }] : []),
                          ],
                          token_bucket: {
                            max_tokens: rateLimit.maxTokens,
                            tokens_per_fill: rateLimit.tokensPerFill,
                            fill_interval: rateLimit.fillInterval,
                          },
                        };
                      }),
                    },
                  },
                },
              },
            },
          ],
        },
      },
      { parent: this }
    );

    this.registerOutputs({
      envoyFilter: this.envoyFilter,
    });
  }
}
