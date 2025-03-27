// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  walletSchema,
  ConfigProvider,
  useConfig as useConfigFromContext,
  pollIntervalSchema,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React from 'react';
import { z } from 'zod';

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  spliceInstanceNames: spliceInstanceNamesSchema,
  pollInterval: pollIntervalSchema,
  services: z.object({
    wallet: walletSchema,
    scan: serviceSchema,
    splitwell: serviceSchema,
    jsonApi: serviceSchema,
  }),
});

type SplitwellServicesStaticConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  splitwell: z.infer<typeof serviceSchema>;
  jsonApi: z.infer<typeof serviceSchema>;
};

type SplitwellStaticConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SplitwellServicesStaticConfig;
};

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
  pollInterval?: z.infer<typeof pollIntervalSchema>;
  services: SplitwellServicesConfig;
};

export const ConfigContext = React.createContext<SplitwellStaticConfig | undefined>(undefined);

export const SplitwellStaticConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useConfig = (): SplitwellConfig => {
  return useConfigFromContext<SplitwellStaticConfig>(ConfigContext);
};

export const useConfigPollInterval: () => number = () => {
  const config = useConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};
