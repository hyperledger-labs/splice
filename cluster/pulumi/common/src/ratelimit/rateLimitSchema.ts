// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

export const BucketRateLimitSchema = z.object({
  maxTokens: z.number(),
  tokensPerFill: z.number(),
  fillInterval: z.string(),
});

export const BannedSchema = z.object({
  type: z.literal('banned'),
  reason: z.string().optional(),
});

export const UnlimitedSchema = z.object({
  type: z.literal('unlimited'),
});

export const RateLimitConfigSchema = z.union([
  BucketRateLimitSchema,
  BannedSchema,
  UnlimitedSchema,
]);

export type ExternalRateLimit = z.infer<typeof RateLimitSchema>;

export const RateLimitSchema = z.object({
  globalLimits: BucketRateLimitSchema,
  rateLimits: z.array(
    z.object({
      actions: z.array(
        z.union([
          z.object({
            name: z.string(),
            pathPrefix: z.string(),
          }),
          z.object({
            name: z.string(),
            clientIp: z.boolean(),
          }),
        ])
      ),
      limits: RateLimitConfigSchema,
    })
  ),
});
