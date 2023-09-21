import { ConfigReader, authSchema, serviceSchema, testAuthSchema } from 'common-frontend';
import { z } from 'zod';

const walletSchema = z.object({
  uiUrl: z.string().url(),
});

type DirectoryServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  directory: z.infer<typeof serviceSchema>;
  jsonApi: z.infer<typeof serviceSchema>;
};

type DirectoryConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: DirectoryServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: z.object({
        uiUrl: z.string().url(),
      }),
      directory: serviceSchema,
      jsonApi: serviceSchema,
    }),
  })
);

export const config: DirectoryConfig = reader.loadConfig();
