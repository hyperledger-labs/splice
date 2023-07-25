import {
  authSchema,
  ConfigReader,
  testAuthSchema,
  serviceSchema,
  useAppManagerConfig,
  appManagerAuthConfig,
  walletSchema,
} from 'common-frontend';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema.optional(),
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: walletSchema.optional(),
      scan: serviceSchema,
      directory: serviceSchema,
      splitwell: serviceSchema,
      jsonApi: serviceSchema.optional(),
    }),
  })
);

export type SplitwellServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  directory: z.infer<typeof serviceSchema>;
  splitwell: z.infer<typeof serviceSchema>;
  jsonApi: z.infer<typeof serviceSchema>;
};

export type SplitwellConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: SplitwellServicesConfig;
};

const staticConfig = reader.loadConfig();

const mandatoryConfig = <T>(name: string, config: T | undefined): T => {
  if (!config) {
    throw new Error(`${name} was not specified in app manager config or in static config`);
  }
  return config;
};

export const useConfig = (): SplitwellConfig => {
  const appManagerConfig = useAppManagerConfig();
  const authConfig: z.infer<typeof authSchema> = mandatoryConfig(
    'auth',
    appManagerConfig ? appManagerAuthConfig(appManagerConfig.oidcAuthority) : staticConfig.auth
  );
  const walletConfig: z.infer<typeof walletSchema> = mandatoryConfig(
    'wallet',
    appManagerConfig ? { uiUrl: appManagerConfig.wallet } : staticConfig.services.wallet
  );
  const jsonApiConfig: z.infer<typeof serviceSchema> = mandatoryConfig(
    'json-api',
    appManagerConfig ? { url: appManagerConfig.jsonApi } : staticConfig.services.jsonApi
  );
  return {
    auth: authConfig,
    testAuth: staticConfig.testAuth,
    services: {
      wallet: walletConfig,
      scan: staticConfig.services.scan,
      directory: staticConfig.services.directory,
      splitwell: staticConfig.services.splitwell,
      jsonApi: jsonApiConfig,
    },
  };
};
