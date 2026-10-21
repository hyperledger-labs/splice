// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { isIP } from 'net';
import { z } from 'zod';

export const BucketRateLimitSchema = z.object({
  maxTokens: z.number(),
  tokensPerFill: z.number(),
  fillInterval: z.string(),
});

const Ipv4AddressSchema = z.string().refine(ip => isIP(ip) === 4, {
  message: 'Expected IPv4 address',
});

const OverrideSchema = BucketRateLimitSchema.extend({
  ips: z.array(Ipv4AddressSchema).min(1),
});

export const PerIpLimitsSchema = BucketRateLimitSchema.extend({
  overrides: z.record(z.string().min(1), OverrideSchema).optional(),
});

export const RateLimitConfigSchema = BucketRateLimitSchema.extend({
  type: z.literal('limited'),
  perIpLimits: PerIpLimitsSchema.optional(),
});

export type ExternalRateLimit = z.infer<typeof RateLimitSchema>;

export const defaultGlobalLimits = {
  maxTokens: 10000,
  tokensPerFill: 10000,
  fillInterval: '60s',
};

export const defaultGlobalPerIpLimits = {
  maxTokens: 1000,
  tokensPerFill: 1000,
  fillInterval: '60s',
};

export const RateLimitSchema = z.object({
  globalLimits: BucketRateLimitSchema.default(defaultGlobalLimits),
  globalPerIpLimits: PerIpLimitsSchema.default(defaultGlobalPerIpLimits),
  // TODO(canton-network/splice/pull#7797) consider removing, depending on what we decide to do with per-endpoint rate limits
  rateLimits: z
    .object({})
    .catchall(
      z.intersection(
        z.object({
          name: z.string(),
        }),
        RateLimitConfigSchema
      )
    )
    .optional(),
});
