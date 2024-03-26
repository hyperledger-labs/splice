import { ConfigReader, authSchema, serviceSchema, testAuthSchema } from 'common-frontend';
import { z } from 'zod';

const walletSchema = z.object({
  uiUrl: z.string().url(),
});

type AnsServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type AnsConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: AnsServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: z.object({
        uiUrl: z.string().url(),
      }),
      scan: serviceSchema,
      validator: serviceSchema,
    }),
  })
);

export const config: AnsConfig = reader.loadConfig();
