// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  ConfigProvider,
  useConfig,
} from 'common-frontend';
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
};

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  services: z.object({
    validator: serviceSchema,
  }),
  spliceInstanceNames: spliceInstanceNamesSchema,
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
