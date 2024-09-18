// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfigProvider,
  serviceSchema,
  spliceInstanceNamesSchema,
  useConfig,
} from 'common-frontend';
import React from 'react';
import { z } from 'zod';

type ScanServicesConfig = {
  scan: z.infer<typeof serviceSchema>;
};

type ScanConfig = {
  services: ScanServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
};

const configScheme = z.object({
  services: z.object({
    scan: serviceSchema,
  }),
  spliceInstanceNames: spliceInstanceNamesSchema,
});

export const ConfigContext = React.createContext<ScanConfig | undefined>(undefined);

export const ScanConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useScanConfig: () => ScanConfig = () => useConfig<ScanConfig>(ConfigContext);
