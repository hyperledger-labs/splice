import { ConfigReader, authSchema, testAuthSchema, serviceSchema } from 'common-frontend';
import { z } from 'zod';

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

export const config = reader.loadConfig();
