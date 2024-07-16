// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  ConfigReader,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  useAppManagerConfig,
  appManagerAuthConfig,
  walletSchema,
} from 'common-frontend';
import { z } from 'zod';

const reader = new ConfigReader(
  z.object({
    auth: authSchema.optional(),
    testAuth: testAuthSchema.optional(),
    spliceInstanceNames: spliceInstanceNamesSchema,
    services: z.object({
      wallet: walletSchema.optional(),
      scan: serviceSchema,
      splitwell: serviceSchema,
      jsonApi: serviceSchema.optional(),
    }),
  })
);

type SplitwellServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  splitwell: z.infer<typeof serviceSchema>;
  jsonApi: z.infer<typeof serviceSchema>;
};

type SplitwellConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SplitwellServicesConfig;
  appManager: boolean;
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
    appManagerConfig
      ? appManagerAuthConfig(appManagerConfig.clientId, appManagerConfig.oidcAuthority)
      : staticConfig.auth
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
    spliceInstanceNames: staticConfig.spliceInstanceNames,
    services: {
      wallet: walletConfig,
      scan: staticConfig.services.scan,
      splitwell: staticConfig.services.splitwell,
      jsonApi: jsonApiConfig,
    },
    appManager: !!appManagerConfig,
  };
};
