// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

/* TODO implement this

* Read scan.yaml and ensure every endpoint matches a actions.pathPrefix entry in the RateLimitEnvoyFilterArgs rateLimits.
    * pathPrefix starts with /api/scan in RateLimitEnvoyFilterArgs but not in scan.yaml; only consider the subset of pathPrefix items that start with /api/scan
    * If any scan.yaml endpoint doesn't have a matching prefix, error with the set of endpoints with missing prefixes in the RateLimitEnvoyFilter constructor
    * If there is a prefix that begins with /api/scan in RateLimitEnvoyFilterArgs but doesn’t match any endpoint in scan.yaml, also error about that. Report both types of errors at the same time, don’t fail fast for one or the other
    * scan.yaml is in apps/scan/src/main/openapi and is in openapi format
    * a scan.yaml endpoint is a yaml key immediately under paths ; { starts a wildcard variable match so should not be counted against the pathPrefix

* Add union of un-banned path prefixes to cloud armor throttle rule in cloudArmor.ts
    * pathExpr is limited to 1024 characters by Google. So switch the call to startsWith to use matches instead, combine all the paths with |, and as a special case collect paths that start with /api/scan and factor that part of the string out of the regexp union
    * the function to extract path prefixes and their banned/unbanned status should be exported from envoyRateLimiter.ts and also used in the code that checks for scan.yaml coverage
    * This is needed because otherwise more requests guaranteed not to reach scan would still count towards overall throttle; spamming 404 paths would be an easy DoS

* in deployment/config.yaml, add pathPrefixes under sv.scan.externalRateLimits.rateLimits setting the unlimited mode for most endpoints except /v0/acs, which is already covered
    * if scan.yaml says an endpoint is deprecated, set it to banned mode instead. Put a comment explaining why for each banned case


For your clarification questions
1. Yes, just strip to /v0/domains in that case.
2. All paths in scan.yaml start with /api/scan, this prefix is just not included in scan.yaml. Treat /readyz like any other endpoint.
3. They should share one limit.
4. They should be unified, only edit deployment/config.yaml.

Further plan modifications:
* There is no need to introduce a function determining deprecated status. Just do that statically, and statically decide on banned/unbanned status when making changes to the yaml file.
* Instead of using 0, 0 for banned cases, change `limits: Limits` below to `limits: Limits | Banned`. Define the new type Banned to introduce a discriminating key. When handling this case in RateLimitEnvoyFilter, skip it instead of actually setting the limit to 0, and add a TODO explaining that banning for real needs a special short-circuit for whitelisted IPs.
* Similarly, add another type Unlimited to the union for the unlimited cases. In this case, don't create an EnvoyFilter because the default filter is already present, and is the correct one.
* Use /api/scan/ as the common prefix for cloudArmor regex instead of /api/scan, because every endpoint always starts with /.
* Where you use the variable configuredPrefixes, say configuredScanPrefixes instead.
* There is no need for new dependencies. See cluster/pulumi/common/src/config/configLoader.ts for the function readAndParseYaml that can be used.
 */

interface Limits {
  maxTokens: number;
  tokensPerFill: number;
  fillInterval: string;
}

export interface RateLimitEnvoyFilterArgs {
  namespace: string;

  appLabel: pulumi.Input<string>;

  inboundPort: pulumi.Input<number>;

  /**
   * Used when no descriptors match the request.
   * */
  globalLimits: Limits;

  rateLimits?: // all the rate limits must be respected, there's an AND relationship between them
  {
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
    limits: Limits;
  }[];
}

export class RateLimitEnvoyFilter extends pulumi.ComponentResource {
  public readonly envoyFilter: k8s.apiextensions.CustomResource;

  constructor(
    name: string,
    args: RateLimitEnvoyFilterArgs,
    opts?: pulumi.ComponentResourceOptions
  ) {
    super('splice:RateLimit', `splice-${args.namespace}-${name}`, args, opts);

    const rateLimitActions: unknown[] =
      args.rateLimits?.map(rateLimit => {
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
                      descriptors: args.rateLimits?.map(rateLimit => {
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
