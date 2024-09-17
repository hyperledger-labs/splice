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

type SvServicesConfig = {
  sv: z.infer<typeof serviceSchema>;
};

type SvConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SvServicesConfig;
};

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  spliceInstanceNames: spliceInstanceNamesSchema,
  services: z.object({
    sv: serviceSchema,
  }),
});

export const ConfigContext = React.createContext<SvConfig | undefined>(undefined);

export const SvConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useSvConfig: () => SvConfig = () => useConfig<SvConfig>(ConfigContext);
