import { ConfigReader } from 'common-frontend';
import { authSchema, testAuthSchema, serviceSchema } from 'common-frontend/lib/config/schema';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: serviceSchema,
      validator: serviceSchema,
      directory: serviceSchema,
      scan: serviceSchema,
    }),
  })
);
export const config = reader.loadConfig();
