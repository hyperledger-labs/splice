import { ConfigReader } from 'common-frontend';
import { serviceSchema } from 'common-frontend/lib/config/schema';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    services: z.object({
      sv: serviceSchema,
    }),
  })
);

export const config = reader.loadConfig();
