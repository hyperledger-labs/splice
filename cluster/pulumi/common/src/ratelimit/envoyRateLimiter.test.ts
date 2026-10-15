// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import {
  buildEndpointRateLimitDescriptors,
  buildGlobalPerIpRateLimitAction,
  buildGlobalPerIpRateLimitDescriptors,
  buildHttpFilterPatches,
  buildPerEndpointPerIpRateLimitDescriptors,
  buildRateLimitActions,
  buildRateLimitFilters,
  buildTypedPerFilterConfig,
  buildXffNumTrustedHopsPatch,
  directIngressXffNumTrustedHops,
  extractPathPrefixes,
  gkeL7GatewayNumTrustedProxies,
  globalPerIpRateLimitFilterName,
  globalPerIpRateLimitStatPrefix,
  globalRateLimitFilterName,
  globalRateLimitStatPrefix,
  parseFillIntervalMs,
  perEndpointPerIpRateLimitFilterName,
  perEndpointPerIpRateLimitStatPrefix,
  perEndpointRateLimitFilterName,
  perEndpointRateLimitStatPrefix,
  rateLimiterLabel,
  rateLimiterMetricPrefix,
  rateLimiterMetricRelabelings,
  validateEffectiveRateLimits,
  validateGrpcPathPrefixes,
  validateTokenBuckets,
} from './envoyRateLimiter';

const baseLimits = {
  maxTokens: 720,
  tokensPerFill: 720,
  fillInterval: '60s',
};

const perIpLimits = {
  maxTokens: 120,
  tokensPerFill: 120,
  fillInterval: '60s',
};

const globalLimits = {
  maxTokens: 10000,
  tokensPerFill: 10000,
  fillInterval: '60s',
};

const globalPerIpLimits = {
  maxTokens: 1000,
  tokensPerFill: 1000,
  fillInterval: '60s',
};

const unlimited = {
  max_tokens: 4294967295,
  tokens_per_fill: 4294967295,
  fill_interval: '60s',
};

// a single endpoint with per-IP limits, the simplest configuration exercising all four limits
const singleEndpointRateLimits = {
  '/registry/metadata/v1/info': {
    name: 'registry-metadata-info',
    type: 'limited' as const,
    ...baseLimits,
    perIpLimits,
  },
};

const multiIpOverride = {
  'multi-validators': {
    ips: ['192.68.78.51', '192.68.78.52'],
    maxTokens: 250,
    tokensPerFill: 250,
    fillInterval: '60s',
  },
};

test('extractPathPrefixes keeps only the externally reachable prefixes', () => {
  expect(
    extractPathPrefixes({
      '/api/scan/v0/acs': { name: 'acs', type: 'limited', ...baseLimits },
      '/registry/metadata/v1/info': { name: 'info', type: 'limited', ...baseLimits },
      '/api/internal/status': { name: 'status', type: 'limited', ...baseLimits },
    })
  ).toEqual(['/api/scan/v0/acs', '/registry/metadata/v1/info']);
  expect(extractPathPrefixes(undefined)).toEqual([]);
});

test('validateGrpcPathPrefixes accepts gRPC method paths', () => {
  expect(() =>
    validateGrpcPathPrefixes({
      '/com.digitalasset.canton.sequencer.api.v30.SequencerService/': {
        name: 'sequencer-service',
        type: 'limited',
        ...baseLimits,
      },
      '/com.digitalasset.canton.sequencer.api.v30.SequencerService/Subscribe': {
        name: 'sequencer-subscribe',
        type: 'limited',
        ...baseLimits,
      },
    })
  ).not.toThrow();
  expect(() => validateGrpcPathPrefixes(undefined)).not.toThrow();
});

test('validateGrpcPathPrefixes rejects prefixes that could never match a gRPC request', () => {
  // HTTP endpoints, a missing leading slash, a service without a package and a bare service name
  // all fail to match the `/<fully.qualified.Service>/<Method>` path gRPC actually sends, so the
  // limit would silently never be enforced.
  [
    '/api/scan/v0/acs',
    'com.digitalasset.canton.sequencer.api.v30.SequencerService/',
    '/SequencerService/Subscribe',
    '/com.digitalasset.canton.sequencer.api.v30.SequencerService',
  ].forEach(pathPrefix => {
    expect(() =>
      validateGrpcPathPrefixes({
        [pathPrefix]: { name: 'some-limit', type: 'limited', ...baseLimits },
      })
    ).toThrow(/invalid gRPC path prefixes/);
  });
});

test('buildEndpointRateLimitDescriptors generates one bucket per endpoint', () => {
  expect(buildEndpointRateLimitDescriptors(singleEndpointRateLimits)).toEqual([
    {
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
      token_bucket: {
        max_tokens: 720,
        tokens_per_fill: 720,
        fill_interval: '60s',
      },
    },
  ]);
});

test('buildPerEndpointPerIpRateLimitDescriptors generates a bucket per endpoint and client IP', () => {
  expect(buildPerEndpointPerIpRateLimitDescriptors(singleEndpointRateLimits)).toEqual([
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
      token_bucket: {
        max_tokens: 120,
        tokens_per_fill: 120,
        fill_interval: '60s',
      },
    },
  ]);
});

test('buildPerEndpointPerIpRateLimitDescriptors emits one descriptor per overridden IP, before the wildcard one', () => {
  expect(
    buildPerEndpointPerIpRateLimitDescriptors({
      '/registry/metadata/v1/info': {
        name: 'registry-metadata-info',
        type: 'limited',
        ...baseLimits,
        perIpLimits: { ...perIpLimits, overrides: multiIpOverride },
      },
    })
  ).toEqual([
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address', value: '192.68.78.51/32' },
      ],
      token_bucket: { max_tokens: 250, tokens_per_fill: 250, fill_interval: '60s' },
    },
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address', value: '192.68.78.52/32' },
      ],
      token_bucket: { max_tokens: 250, tokens_per_fill: 250, fill_interval: '60s' },
    },
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
      token_bucket: { max_tokens: 120, tokens_per_fill: 120, fill_interval: '60s' },
    },
  ]);
});

test('buildGlobalPerIpRateLimitDescriptors emits a wildcard bucket, preceded by the IP overrides', () => {
  // without overrides there is a single bucket per observed client IP
  expect(buildGlobalPerIpRateLimitDescriptors(globalPerIpLimits)).toEqual([
    {
      entries: [{ key: 'masked_remote_address' }],
      token_bucket: { max_tokens: 1000, tokens_per_fill: 1000, fill_interval: '60s' },
    },
  ]);

  expect(
    buildGlobalPerIpRateLimitDescriptors({ ...globalPerIpLimits, overrides: multiIpOverride })
  ).toEqual([
    {
      entries: [{ key: 'masked_remote_address', value: '192.68.78.51/32' }],
      token_bucket: { max_tokens: 250, tokens_per_fill: 250, fill_interval: '60s' },
    },
    {
      entries: [{ key: 'masked_remote_address', value: '192.68.78.52/32' }],
      token_bucket: { max_tokens: 250, tokens_per_fill: 250, fill_interval: '60s' },
    },
    {
      entries: [{ key: 'masked_remote_address' }],
      token_bucket: { max_tokens: 1000, tokens_per_fill: 1000, fill_interval: '60s' },
    },
  ]);
});

test('buildRateLimitFilters installs one filter per limit, from the most to the least specific', () => {
  const config = buildTypedPerFilterConfig(
    buildRateLimitFilters(globalLimits, globalPerIpLimits, singleEndpointRateLimits)
  );

  // envoy consumes at most one descriptor bucket per filter, so limits that must all be
  // respected are enforced by separate filters. They run in this order, so that a request
  // rejected by a specific limit does not consume the tokens of the broader buckets.
  expect(Object.keys(config)).toEqual([
    perEndpointPerIpRateLimitFilterName,
    perEndpointRateLimitFilterName,
    globalPerIpRateLimitFilterName,
    globalRateLimitFilterName,
  ]);

  const filterConfig = (name: string) => config[name] as Record<string, unknown>;

  // only the global filter limits through its default bucket, which every request consumes
  expect(filterConfig(globalRateLimitFilterName).always_consume_default_token_bucket).toBe(true);
  expect(filterConfig(globalRateLimitFilterName).token_bucket).toEqual({
    max_tokens: 10000,
    tokens_per_fill: 10000,
    fill_interval: '60s',
  });
  // and it enforces nothing else, so that a rejection is unambiguously attributed to it
  expect(filterConfig(globalRateLimitFilterName).descriptors).toEqual([]);

  // the filters whose limits are all expressed as descriptors must not limit the requests
  // matching none of them
  [
    perEndpointPerIpRateLimitFilterName,
    perEndpointRateLimitFilterName,
    globalPerIpRateLimitFilterName,
  ].forEach(name => {
    expect(filterConfig(name).always_consume_default_token_bucket).toBe(false);
    expect(filterConfig(name).token_bucket).toEqual(unlimited);
  });

  expect(filterConfig(perEndpointRateLimitFilterName).descriptors).toEqual([
    {
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
      token_bucket: { max_tokens: 720, tokens_per_fill: 720, fill_interval: '60s' },
    },
  ]);
  expect(filterConfig(perEndpointPerIpRateLimitFilterName).descriptors).toEqual([
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
      token_bucket: { max_tokens: 120, tokens_per_fill: 120, fill_interval: '60s' },
    },
  ]);
  expect(filterConfig(globalPerIpRateLimitFilterName).descriptors).toEqual([
    {
      entries: [{ key: 'masked_remote_address' }],
      token_bucket: { max_tokens: 1000, tokens_per_fill: 1000, fill_interval: '60s' },
    },
  ]);
});

test('the per-endpoint filters are omitted when nothing is configured for them', () => {
  // no endpoint configures per-IP limits
  expect(
    buildRateLimitFilters(globalLimits, globalPerIpLimits, {
      '/registry/metadata/v1/info': {
        name: 'registry-metadata-info',
        type: 'limited',
        ...baseLimits,
      },
    }).map(filter => filter.name)
  ).toEqual([
    perEndpointRateLimitFilterName,
    globalPerIpRateLimitFilterName,
    globalRateLimitFilterName,
  ]);

  // no endpoint limits at all
  expect(
    buildRateLimitFilters(globalLimits, globalPerIpLimits, {}).map(filter => filter.name)
  ).toEqual([globalPerIpRateLimitFilterName, globalRateLimitFilterName]);
});

test('buildHttpFilterPatches keeps the filter order by pinning the insertion point to the router', () => {
  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, singleEndpointRateLimits);
  const patches = buildHttpFilterPatches(filters, 5008) as {
    applyTo: string;
    match: {
      listener: { portNumber: number; filterChain: { filter: { subFilter?: { name: string } } } };
    };
    patch: { operation: string; value: { name: string; typed_config: { value: unknown } } };
  }[];

  // istio applies the patches in order and each of them inserts right before the router, which is
  // always the last filter of the chain, so the chain order is the order of buildRateLimitFilters.
  // Without the subFilter match istio would insert every filter before the *first* one, which
  // would silently reverse them and make the global limit run first.
  patches.forEach(patch => {
    expect(patch.applyTo).toEqual('HTTP_FILTER');
    expect(patch.patch.operation).toEqual('INSERT_BEFORE');
    expect(patch.match.listener.filterChain.filter.subFilter).toEqual({
      name: 'envoy.filters.http.router',
    });
    // only the port carrying the externally reachable API is rate limited; the workload's other
    // inbound listeners (e.g. the sequencer's admin and peer-to-peer ports, which istio also
    // treats as HTTP) must keep their filter chain untouched
    expect(patch.match.listener.portNumber).toEqual(5008);
  });
  expect(patches.map(patch => patch.patch.value.name)).toEqual(filters.map(filter => filter.name));
  // the filters are configured per route, the chain only declares them with their stat prefix
  expect(patches[0].patch.value.typed_config.value).toEqual({
    stat_prefix: perEndpointPerIpRateLimitStatPrefix,
  });
});

test('buildXffNumTrustedHopsPatch keys the per-IP buckets on the client behind extra proxies', () => {
  const patch = buildXffNumTrustedHopsPatch(5008, gkeL7GatewayNumTrustedProxies) as {
    applyTo: string;
    match: {
      context: string;
      listener: { portNumber: number; filterChain: { filter: { name: string } } };
    };
    patch: { operation: string; value: { typed_config: Record<string, unknown> } };
  };

  expect(patch.applyTo).toEqual('NETWORK_FILTER');
  expect(patch.match.context).toEqual('SIDECAR_INBOUND');
  // like the http filter patches, only the externally reachable port is patched
  expect(patch.match.listener.portNumber).toEqual(5008);
  expect(patch.match.listener.filterChain.filter.name).toEqual(
    'envoy.filters.network.http_connection_manager'
  );
  // merged into the connection manager istio generates, which must keep all its other settings
  expect(patch.patch.operation).toEqual('MERGE');
  // istiod merges the typed config into the one it generated and panics on a descriptor mismatch,
  // dropping every listener patch, so this must be the concrete type and not a TypedStruct
  expect(patch.patch.value.typed_config).toEqual({
    '@type':
      'type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager',
    xff_num_trusted_hops: gkeL7GatewayNumTrustedProxies,
  });
});

test('the filters are distinguishable in the metrics and in the access logs', () => {
  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, singleEndpointRateLimits);

  // envoy does not label the local rate limit metrics, the stat prefix is the only distinction,
  // so no two limits may share one
  expect(filters.map(f => f.statPrefix)).toEqual([
    perEndpointPerIpRateLimitStatPrefix,
    perEndpointRateLimitStatPrefix,
    globalPerIpRateLimitStatPrefix,
    globalRateLimitStatPrefix,
  ]);
  expect(new Set(filters.map(f => f.statPrefix)).size).toEqual(filters.length);

  // and the response header identifies the limit that rejected a request in the access logs
  const config = buildTypedPerFilterConfig(filters);
  const headerValues = filters.map(filter => {
    const filterConfig = config[filter.name] as Record<string, unknown>;
    const headers = filterConfig.response_headers_to_add as {
      header: { key: string; value: string };
    }[];
    expect(headers[0].header.key).toEqual('x-local-rate-limit');
    return headers[0].header.value;
  });
  // the same names are used for the `limiter` metric label, so that a rejection can be correlated
  // between the metrics and the access logs
  expect(headerValues).toEqual(
    filters.map(filter => filter.statPrefix.replace(/^.*_limiter_/, ''))
  );
});

test('the metric relabelings merge the filter metrics into one metric labeled by limiter', () => {
  const [extractLimiter, normalizeName] = rateLimiterMetricRelabelings;

  const relabel = (metric: string) => {
    const limiter = new RegExp(`^${extractLimiter.regex}$`).exec(metric);
    const name = new RegExp(`^${normalizeName.regex}$`).exec(metric);
    return {
      [rateLimiterLabel]: limiter?.[1],
      __name__: name ? `${rateLimiterMetricPrefix}_${name[1]}` : undefined,
    };
  };

  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, singleEndpointRateLimits);

  // every limit gets its own `limiter` label, in particular the global and the per-endpoint ones,
  // which are enforced by two separate filters
  expect(
    filters.map(filter => relabel(`envoy_${filter.statPrefix}_http_local_rate_limit_enforced`))
  ).toEqual([
    { limiter: 'endpoint_per_ip', __name__: 'envoy_http_local_rate_limit_enforced' },
    { limiter: 'endpoint', __name__: 'envoy_http_local_rate_limit_enforced' },
    { limiter: 'per_ip', __name__: 'envoy_http_local_rate_limit_enforced' },
    { limiter: 'global', __name__: 'envoy_http_local_rate_limit_enforced' },
  ]);
  // all the counters of the filter are covered
  expect(
    ['enabled', 'ok', 'rate_limited', 'enforced'].map(counter =>
      relabel(`envoy_${globalPerIpRateLimitStatPrefix}_http_local_rate_limit_${counter}`)
    )
  ).toEqual(
    ['enabled', 'ok', 'rate_limited', 'enforced'].map(counter => ({
      limiter: 'per_ip',
      __name__: `envoy_http_local_rate_limit_${counter}`,
    }))
  );
  // and unrelated metrics are left alone
  expect(relabel('istio_requests_total')).toEqual({ limiter: undefined, __name__: undefined });
});

test('buildRateLimitActions emits per-endpoint and per-IP actions', () => {
  const actions = buildRateLimitActions(singleEndpointRateLimits);
  const pathMatch = {
    name: ':path',
    string_match: {
      prefix: '/registry/metadata/v1/info',
      ignore_case: true,
    },
  };

  expect(actions).toHaveLength(2);
  expect(actions[0]).toEqual({
    actions: [
      {
        header_value_match: {
          descriptor_value: 'registry-metadata-info',
          expect_match: true,
          headers: [pathMatch],
        },
      },
    ],
  });
  expect(actions[1]).toEqual({
    actions: [
      {
        header_value_match: {
          descriptor_value: 'registry-metadata-info',
          expect_match: true,
          headers: [pathMatch],
        },
      },
      {
        // the raw x-forwarded-for header must not be used, it is attacker controlled
        masked_remote_address: {
          v4_prefix_mask_len: 32,
          v6_prefix_mask_len: 128,
        },
      },
    ],
  });
});

test('buildGlobalPerIpRateLimitAction keys only on the non-spoofable client address', () => {
  expect(buildGlobalPerIpRateLimitAction()).toEqual({
    actions: [
      {
        masked_remote_address: {
          v4_prefix_mask_len: 32,
          v6_prefix_mask_len: 128,
        },
      },
    ],
  });
});

test('buildRateLimitActions makes nested path prefixes mutually exclusive', () => {
  const actions = buildRateLimitActions({
    '/registry/transfer-instruction/v1': {
      name: 'registry-transfer-instruction',
      type: 'limited',
      ...baseLimits,
    },
    '/registry/transfer-instruction/v1/transfer-factory': {
      name: 'registry-transfer-factory',
      type: 'limited',
      ...baseLimits,
    },
  }) as { actions: { header_value_match: { descriptor_value: string; headers: unknown[] } }[] }[];

  // the less specific endpoint excludes the requests matched by the nested one, so that a
  // request never generates two per-endpoint descriptors (envoy would only consume one of them)
  expect(actions[0].actions[0].header_value_match).toEqual({
    descriptor_value: 'registry-transfer-instruction',
    expect_match: true,
    headers: [
      {
        name: ':path',
        string_match: { prefix: '/registry/transfer-instruction/v1', ignore_case: true },
      },
      {
        name: ':path',
        string_match: {
          prefix: '/registry/transfer-instruction/v1/transfer-factory',
          ignore_case: true,
        },
        invert_match: true,
      },
    ],
  });
  // and the most specific endpoint excludes nothing
  expect(actions[1].actions[0].header_value_match.headers).toEqual([
    {
      name: ':path',
      string_match: {
        prefix: '/registry/transfer-instruction/v1/transfer-factory',
        ignore_case: true,
      },
    },
  ]);
});

const envoyFilterArgs = {
  namespace: 'sv-1',
  appLabel: 'scan-app',
  inboundPort: 5012,
  globalLimits,
  globalPerIpLimits,
  rateLimits: {
    '/api/scan/v0/acs': {
      name: 'acs',
      type: 'limited' as const,
      ...baseLimits,
      perIpLimits,
    },
  },
};

test('validateEffectiveRateLimits validates the global per-IP limits and their overrides', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: { maxTokens: 1000, tokensPerFill: 1000, fillInterval: '90s' },
    })
  ).toThrow('globalPerIpLimits: fillInterval');

  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: {
        ...globalPerIpLimits,
        overrides: {
          'single-validator': {
            ips: ['192.68.78.50'],
            maxTokens: 5000,
            tokensPerFill: 5000,
            fillInterval: '90s',
          },
        },
      },
    })
  ).toThrow("globalPerIpLimits override 'single-validator'");
});

test('validateEffectiveRateLimits rejects an IP listed in two overrides', () => {
  const duplicated = {
    'group-a': {
      ips: ['192.68.78.50', '192.68.78.51'],
      maxTokens: 5000,
      tokensPerFill: 5000,
      fillInterval: '60s',
    },
    'group-b': {
      ips: ['192.68.78.51'],
      maxTokens: 5000,
      tokensPerFill: 5000,
      fillInterval: '60s',
    },
  };

  // only one descriptor is consumed per client IP, so an IP listed twice would silently get the
  // limits of one of the two overrides
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: { ...globalPerIpLimits, overrides: duplicated },
    })
  ).toThrow(
    "globalPerIpLimits: duplicate IPs in per-IP rate limits: 192.68.78.51 (in override 'group-b')"
  );

  // the per-endpoint per-IP overrides are validated the same way, and reported by path
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      rateLimits: {
        '/api/scan/v0/acs': {
          name: 'acs',
          type: 'limited' as const,
          ...baseLimits,
          perIpLimits: { ...perIpLimits, overrides: duplicated },
        },
      },
    })
  ).toThrow('/api/scan/v0/acs: duplicate IPs in per-IP rate limits: 192.68.78.51');
});

test('validateEffectiveRateLimits rejects reserved descriptor names', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      rateLimits: {
        '/api/scan/v0/acs': {
          name: 'masked_remote_address',
          type: 'limited' as const,
          ...baseLimits,
        },
      },
    })
  ).toThrow('use reserved name');
});

test('validateEffectiveRateLimits rejects two endpoints sharing a name', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      rateLimits: {
        '/api/scan/v0/acs': { name: 'scan', type: 'limited' as const, ...baseLimits },
        '/api/scan/v0/holdings': { name: 'scan', type: 'limited' as const, ...baseLimits },
      },
    })
  ).toThrow('duplicate rate limit names: scan');
});

test('parseFillIntervalMs parses protobuf durations and rejects other formats', () => {
  expect(parseFillIntervalMs('60s', 'ctx')).toEqual(60000);
  expect(parseFillIntervalMs('0.5s', 'ctx')).toEqual(500);
  expect(() => parseFillIntervalMs('500ms', 'ctx')).toThrow('invalid fillInterval');
  expect(() => parseFillIntervalMs('1m', 'ctx')).toThrow('invalid fillInterval');
});

test('validateTokenBuckets accepts intervals that are multiples of the global interval', () => {
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '120s',
        perIpLimits,
      },
    })
  ).not.toThrow();
});

test('validateTokenBuckets rejects intervals that envoy would NACK', () => {
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '90s',
      },
    })
  ).toThrow('must be a multiple of the globalLimits fillInterval');

  // below envoy's 50ms minimum
  expect(() =>
    validateTokenBuckets(
      { maxTokens: 1, tokensPerFill: 1, fillInterval: '0.01s' },
      {
        '/api/scan/v0/acs': {
          name: 'acs',
          type: 'limited',
          maxTokens: 500,
          tokensPerFill: 500,
          fillInterval: '60s',
        },
      }
    )
  ).toThrow('below the 50ms minimum');

  // per-IP overrides are validated as well
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        ...baseLimits,
        perIpLimits: {
          ...perIpLimits,
          overrides: {
            'single-validator': {
              ips: ['192.68.78.50'],
              maxTokens: 220,
              tokensPerFill: 220,
              fillInterval: '90s',
            },
          },
        },
      },
    })
  ).toThrow("perIpLimits override 'single-validator'");
});

test('gRPC rejections are reported as RESOURCE_EXHAUSTED, HTTP ones as 429', () => {
  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, singleEndpointRateLimits);

  // envoy answers a rate limited gRPC call with HTTP 200 and a gRPC status, so the rejection is
  // only recognizable (by the client and in the access logs) via that status
  const grpcConfig = buildTypedPerFilterConfig(filters, 'grpc');
  filters.forEach(filter => {
    expect(
      (grpcConfig[filter.name] as Record<string, unknown>).rate_limited_as_resource_exhausted
    ).toEqual(true);
  });

  // HTTP requests are rejected with 429, the default UNAVAILABLE/RESOURCE_EXHAUSTED distinction
  // does not apply
  const httpConfig = buildTypedPerFilterConfig(filters);
  filters.forEach(filter => {
    expect(
      (httpConfig[filter.name] as Record<string, unknown>).rate_limited_as_resource_exhausted
    ).toEqual(false);
  });
});
