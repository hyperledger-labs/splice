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

type HS256UnsafeAuth = z.infer<typeof hs256UnsafeSchema>;
type AuthConfig = z.infer<typeof authSchema>;

function isHs256UnsafeAuthConfig(obj: AuthConfig): obj is HS256UnsafeAuth {
  return obj.algorithm === Algorithm.HS256UNSAFE;
}

const testAuthSchema = z.object({ secret: z.string() });

export { Algorithm, authSchema, testAuthSchema, isHs256UnsafeAuthConfig };
