// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

const oAuthSchema = z.object({
  kind: z.literal('oauth'),
  oauthDomain: z.string().min(1),
  oauthClientId: z.string().min(1),
  audience: z.string().min(1),
  managementApi: z.object({
    clientId: z.string().min(1),
    clientSecret: z.string().min(1),
  }),
  admin: z.object({
    email: z.string().email(),
    password: z.string().min(1),
  }),
  usersPassword: z.string().min(1),
});

const selfSignedSchema = z.object({
  kind: z.literal('self-signed'),
  user: z.string().min(1),
  audience: z.string().min(1),
  secret: z.string().min(1),
});

const validatorSchema = z.object({
  walletBaseUrl: z.string().min(1),
  auth: z.discriminatedUnion('kind', [oAuthSchema, selfSignedSchema]),
});

export const configSchema = z.object({
  isDevNet: z
    .boolean()
    .or(z.enum(['true', 'false', '1', '0', '']).transform(val => val === 'true' || val === '1')),
  usersPerValidator: z.number().min(1),
  validators: z.array(validatorSchema).min(1),
  test: z.object({
    duration: z.string().min(1),
    iterationsPerMinute: z.coerce.number().min(1),
    preAllocatedVUs: z.coerce.number().default(20),
    maxVUs: z.coerce.number().default(100),
  }),
  adaptiveScenario: z.object({
    enabled: z.boolean(),
    maxVUs: z.number(),
    duration: z.string(),
  }),
});

export type Config = z.infer<typeof configSchema>;

const config: Config = configSchema.parse(JSON.parse(__ENV.EXTERNAL_CONFIG));

export default {
  ...config,
  options: {
    scenarios: {
      ...(config.adaptiveScenario.enabled
        ? {
            adaptive_load: {
              executor: 'externally-controlled',
              vus: 1,
              maxVUs: config.adaptiveScenario.maxVUs,
              // How long the test lasts
              duration: config.adaptiveScenario.duration,
            },
          }
        : {}),
      generate_load: {
        executor: 'constant-arrival-rate',

        // How long the test lasts
        duration: config.test.duration,

        // How many iterations per timeUnit
        rate: config.test.iterationsPerMinute,
        timeUnit: '1m',

        preAllocatedVUs: config.test.preAllocatedVUs,
        maxVUs: config.test.maxVUs,
      },
    },
  },
};
