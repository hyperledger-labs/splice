import { authSchema, ConfigReader, testAuthSchema, serviceSchema } from 'common-frontend';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: z.object({
        uiUrl: z.string().url(),
      }),
      scan: serviceSchema,
      directory: serviceSchema,
      splitwell: serviceSchema,
      jsonApi: serviceSchema,
    }),
  })
);

export const config = reader.loadConfig();
