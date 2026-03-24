// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

export const BucketRateLimitSchema = z.object({
  maxTokens: z.number(),
  tokensPerFill: z.number(),
  fillInterval: z.string(),
});

const BucketMatchedRateLimitSchema = BucketRateLimitSchema.extend({
  clientIp: z.boolean(),
});

export const BannedSchema = z.object({
  type: z.literal('banned'),
});

export const UnlimitedSchema = z.object({
  type: z.literal('unlimited'),
});

export const RateLimitConfigSchema = z.xor([
  BucketMatchedRateLimitSchema,
  BannedSchema,
  UnlimitedSchema,
]);

export type ExternalRateLimit = z.infer<typeof RateLimitSchema>;

export const RateLimitSchema = z.object({
  globalLimits: BucketRateLimitSchema,
  rateLimits: z.object({}).catchall(
    z.intersection(
      z.object({
        name: z.string(),
      }),
      RateLimitConfigSchema
    )
  ),
});
