import { ConfigReader, authSchema, testAuthSchema, serviceSchema } from 'common-frontend';
import { z } from 'zod';

type WalletServicesConfig = {
  directory: z.infer<typeof serviceSchema>;
  scan: z.infer<typeof serviceSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type WalletConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: WalletServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      validator: serviceSchema,
      directory: serviceSchema,
      scan: serviceSchema,
    }),
  })
);

export const config: WalletConfig = reader.loadConfig();
