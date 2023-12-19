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

const config: Config = configSchema.parse(JSON.parse(__ENV.EXTERNAL_CONFIG));

export default {
  ...config,
  options: {
    scenarios: {
      generate_load: {
        executor: 'constant-arrival-rate',

        // How long the test lasts
        duration: config.testDuration,

        // How many iterations per timeUnit
        rate: 45,
        timeUnit: '1m',

        // Pre-allocate VUs
        preAllocatedVUs: 50,
      },
    },
  },
};
