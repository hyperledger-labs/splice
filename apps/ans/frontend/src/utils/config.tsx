// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  spliceInstanceNamesSchema,
  serviceSchema,
  testAuthSchema,
  ConfigProvider,
  useConfig,
} from 'common-frontend';
import React from 'react';
import { z } from 'zod';

const walletSchema = z.object({
  uiUrl: z.string().url(),
});

type AnsServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type AnsConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: AnsServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
};

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  services: z.object({
    wallet: z.object({
      uiUrl: z.string().url(),
    }),
    validator: serviceSchema,
  }),
  spliceInstanceNames: spliceInstanceNamesSchema,
});

export const ConfigContext = React.createContext<AnsConfig | undefined>(undefined);

export const AnsConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useAnsConfig: () => AnsConfig = () => useConfig<AnsConfig>(ConfigContext);
