// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  pollIntervalSchema,
  ConfigProvider,
  useConfig,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React from 'react';
import { z } from 'zod';

type WalletServicesConfig = {
  validator: z.infer<typeof serviceSchema>;
};

type WalletConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: WalletServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  pollInterval?: z.infer<typeof pollIntervalSchema>;
};

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  services: z.object({
    validator: serviceSchema,
  }),
  spliceInstanceNames: spliceInstanceNamesSchema,
  pollInterval: pollIntervalSchema,
});

export const ConfigContext = React.createContext<WalletConfig | undefined>(undefined);

export const WalletConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useWalletConfig: () => WalletConfig = () => useConfig<WalletConfig>(ConfigContext);

export const useConfigPollInterval: () => number = () => {
  const config = useWalletConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};
