import { z } from 'zod';

export const configSchema = z.object({
  testDuration: z.string().min(1),
  walletBaseUrl: z.string().min(1),
  auth: z.object({
    oauthDomain: z.string().min(1),
    oauthClientId: z.string().min(1),
    userCredentials: z.string().min(1),
  }),
});

export type Config = z.infer<typeof configSchema>;
