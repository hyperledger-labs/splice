import { ConfigReader, authSchema, testAuthSchema, serviceSchema } from 'common-frontend';
import { z } from 'zod';

type SvServicesConfig = {
  sv: z.infer<typeof serviceSchema>;
};

type SvConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: SvServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      sv: serviceSchema,
    }),
  })
);

export const config: SvConfig = reader.loadConfig();
