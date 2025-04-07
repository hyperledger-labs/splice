// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

enum Algorithm {
  RS256 = 'rs-256',
  HS256UNSAFE = 'hs-256-unsafe',
}

const tokenRequestSchema = z.object({
  token_audience: z.string(), // Request an access token from IAM provider with this audience
  token_scope: z.string().optional(), // Request an access token from IAM provider with this scope
});

const rs256Schema = tokenRequestSchema
  .extend({
    algorithm: z.literal(Algorithm.RS256),
    authority: z.string().url(),
    client_id: z.string(),
  })
  .strict();

const hs256UnsafeSchema = tokenRequestSchema
  .extend({
    algorithm: z.literal(Algorithm.HS256UNSAFE),
    secret: z.string(),
  })
  .strict();

const authSchema = z.discriminatedUnion('algorithm', [rs256Schema, hs256UnsafeSchema]);

type Hs256UnsafeAuth = z.infer<typeof hs256UnsafeSchema>;
type AuthConfig = z.infer<typeof authSchema>;

const isHs256UnsafeAuthConfig = (obj: AuthConfig): obj is Hs256UnsafeAuth => {
  return obj.algorithm === Algorithm.HS256UNSAFE;
};

const getHs256UnsafeSecret = (obj: AuthConfig): string => {
  if (isHs256UnsafeAuthConfig(obj)) {
    return obj.secret;
  } else {
    throw new Error('Attempted to get Hs256UnsafeSecret on auth config that is not Hs256Unsafe');
  }
};

const testAuthSchema = tokenRequestSchema.extend({ secret: z.string() });
type TestAuthConfig = z.infer<typeof testAuthSchema>;

export {
  Algorithm,
  AuthConfig, // used for UserContext
  TestAuthConfig, // used for UserContext and Login
  authSchema,
  testAuthSchema,
  isHs256UnsafeAuthConfig,
  getHs256UnsafeSecret,
};
