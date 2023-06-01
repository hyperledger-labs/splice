import { ConfigReader, serviceSchema } from 'common-frontend';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    services: z.object({
      scan: serviceSchema,
    }),
  })
);

export const config = reader.loadConfig();
