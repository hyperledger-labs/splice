// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import { RateLimitSchema } from './rateLimitSchema';

const validConfig = {
  globalLimits: {
    maxTokens: 1000,
    tokensPerFill: 1000,
    fillInterval: '60s',
  },
};

test('RateLimitSchema accepts config without overrides', () => {
  expect(() => RateLimitSchema.parse(validConfig)).not.toThrow();
});

test('RateLimitSchema defaults to global and global per-IP limits', () => {
  const parsed = RateLimitSchema.parse({});
  expect(parsed).toEqual({
    globalLimits: { maxTokens: 10000, tokensPerFill: 10000, fillInterval: '60s' },
    globalPerIpLimits: { maxTokens: 1000, tokensPerFill: 1000, fillInterval: '60s' },
  });
});

test('RateLimitSchema accepts named overrides on the global per-IP limits', () => {
  const config = {
    ...validConfig,
    globalPerIpLimits: {
      maxTokens: 1000,
      tokensPerFill: 1000,
      fillInterval: '60s',
      overrides: {
        'multi-validators': {
          ips: ['192.68.78.51', '192.68.78.52'],
          maxTokens: 5000,
          tokensPerFill: 5000,
          fillInterval: '60s',
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).not.toThrow();
});

test('RateLimitSchema rejects non-IPv4 addresses in global per-IP overrides', () => {
  const config = {
    ...validConfig,
    globalPerIpLimits: {
      maxTokens: 1000,
      tokensPerFill: 1000,
      fillInterval: '60s',
      overrides: {
        'multi-validators': {
          ips: ['2001:db8::1'],
          maxTokens: 5000,
          tokensPerFill: 5000,
          fillInterval: '60s',
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

test('RateLimitSchema rejects override without ips', () => {
  const config = {
    ...validConfig,
    globalPerIpLimits: {
      maxTokens: 1000,
      tokensPerFill: 1000,
      fillInterval: '60s',
      overrides: {
        'multi-validators': {
          maxTokens: 5000,
          tokensPerFill: 5000,
          fillInterval: '60s',
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

test('RateLimitSchema rejects empty override ips array', () => {
  const config = {
    ...validConfig,
    globalPerIpLimits: {
      maxTokens: 1000,
      tokensPerFill: 1000,
      fillInterval: '60s',
      overrides: {
        'multi-validators': {
          ips: [],
          maxTokens: 5000,
          tokensPerFill: 5000,
          fillInterval: '60s',
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});
