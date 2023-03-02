import { ConfigReader } from 'common-frontend';
import { authSchema, testAuthSchema, serviceSchema } from 'common-frontend/lib/config/schema';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: serviceSchema.extend({
        uiUrl: z.string().url(),
      }),
      scan: serviceSchema,
      directory: serviceSchema,
      splitwell: serviceSchema,
      ledgerApi: serviceSchema,
      participantAdmin: serviceSchema,
    }),
  })
);

export const config = reader.loadConfig();
