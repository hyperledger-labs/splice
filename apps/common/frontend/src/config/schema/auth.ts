import { z } from 'zod';

enum Algorithm {
  RS256 = 'rs-256',
  HS256UNSAFE = 'hs-256-unsafe',
}

const rs256Schema = z
  .object({
    algorithm: z.literal(Algorithm.RS256),
    authority: z.string().url(),
    client_id: z.string(),
    redirect_uri: z.string().url(),
  })
  .strict();

const hs256UnsafeSchema = z
  .object({
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

const testAuthSchema = z.object({ secret: z.string() });
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
