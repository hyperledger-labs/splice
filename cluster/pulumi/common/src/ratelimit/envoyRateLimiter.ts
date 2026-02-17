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

interface Banned {
  type: 'banned';
  reason?: string;
}

interface Unlimited {
  type: 'unlimited';
}

type RateLimitConfig = Limits | Banned | Unlimited;

export interface PathPrefixInfo {
  pathPrefix: string;
  isBanned: boolean;
}

interface LocalLimit<L> {
  actions: (
    | {
        name: string;
        pathPrefix: string;
      }
    | {
        name: string;
        clientIp: boolean;
      }
  )[];
  limits: L;
}

export interface RateLimitEnvoyFilterArgs {
  namespace: string;

  appLabel: pulumi.Input<string>;

  inboundPort: pulumi.Input<number>;

  /**
   * Used when no descriptors match the request.
   * */
  globalLimits: Limits;

  // all the rate limits must be respected, there's an AND relationship between them
  rateLimits?: LocalLimit<RateLimitConfig>[];
}

export function extractPathPrefixes(
  rateLimits?: RateLimitEnvoyFilterArgs['rateLimits']
): PathPrefixInfo[] {
  if (!rateLimits) {
    return [];
  }

  return rateLimits
    .flatMap(rl => {
      const isBanned = 'type' in rl.limits && rl.limits.type === 'banned';
      return rl.actions
        .filter(action => 'pathPrefix' in action)
        .map(action => ({
          pathPrefix: action.pathPrefix,
          isBanned,
        }));
    })
    .filter(info => info.pathPrefix.startsWith('/api/scan'));
}

function validateEndpointCoverage(
  scanEndpoints: string[],
  configuredScanPrefixes: string[]
): { missing: string[]; orphaned: string[] } {
  const missing: string[] = [];
  const orphaned: string[] = [];

  // Check for missing prefixes
  for (const endpoint of scanEndpoints) {
    const hasMatch = configuredScanPrefixes.some(prefix => endpoint.startsWith(prefix));
    if (!hasMatch) {
      missing.push(endpoint);
    }
  }

  // Check for orphaned prefixes
  for (const prefix of configuredScanPrefixes) {
    const hasMatch = scanEndpoints.some(endpoint => endpoint.startsWith(prefix));
    if (!hasMatch) {
      orphaned.push(prefix);
    }
  }

  return { missing, orphaned };
}

function validateEffectiveRateLimits(
  args: RateLimitEnvoyFilterArgs
): LocalLimit<Limits>[] | undefined {
  // Validate scan.yaml endpoint coverage
  const scanEndpoints = parseScanYamlEndpoints();

  const configuredScanPrefixes = (args.rateLimits || [])
    .flatMap(rl => rl.actions)
    .filter(action => 'pathPrefix' in action && action.pathPrefix.startsWith('/api/scan'))
    .map(action => ('pathPrefix' in action ? action.pathPrefix : ''));

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
  return args.rateLimits?.filter((rl): rl is LocalLimit<Limits> => {
    // TODO: in banned case, implement actual banning with special short-circuit for whitelisted IPs
    // Currently skipping banned endpoints instead of setting 0/0 limits
    // in unlimited case, we fall back to globalRateLimit so don't need a rule
    return !('type' in rl.limits);
  });
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
      effectiveRateLimits?.map(rateLimit => {
        return {
          actions: rateLimit.actions.map(action => {
            if ('pathPrefix' in action) {
              return {
                header_value_match: {
                  descriptor_value: action.name,
                  expect_match: true,
                  headers: [
                    {
                      name: ':path',
                      string_match: {
                        prefix: action.pathPrefix,
                        ignore_case: true,
                      },
                    },
                  ],
                },
              };
            } else if (action.clientIp) {
              return {
                request_headers: {
                  descriptor_key: 'client_ip',
                  header_name: 'x-forwarded-for',
                },
              };
            }
            throw new Error(`Unsupported action: ${JSON.stringify(action)}`);
          }),
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
                      descriptors: effectiveRateLimits?.map(rateLimit => {
                        return {
                          entries: rateLimit.actions.map(action => {
                            if ('pathPrefix' in action) {
                              return {
                                key: 'header_match',
                                value: action.name,
                              };
                            } else if (action.clientIp) {
                              return {
                                key: action.name,
                              };
                            }
                            throw new Error(`Unsupported action: ${JSON.stringify(action)}`);
                          }),
                          token_bucket: {
                            max_tokens: rateLimit.limits.maxTokens,
                            tokens_per_fill: rateLimit.limits.tokensPerFill,
                            fill_interval: rateLimit.limits.fillInterval,
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
