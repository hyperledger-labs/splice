import {
  ConfigReader,
  authSchema,
  testAuthSchema,
  serviceSchema,
  walletSchema,
} from 'common-frontend';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      validator: serviceSchema,
      directory: serviceSchema,
      wallet: walletSchema,
    }),
  })
);

export const config = reader.loadConfig();
