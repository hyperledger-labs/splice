import {
  ConfigReader,
  authSchema,
  testAuthSchema,
  serviceSchema,
  walletSchema,
} from 'common-frontend';
import { z } from 'zod';

type AppManagerServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type AppManagerConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: AppManagerServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      validator: serviceSchema,
      scan: serviceSchema,
      wallet: walletSchema,
    }),
  })
);

export const config: AppManagerConfig = reader.loadConfig();
