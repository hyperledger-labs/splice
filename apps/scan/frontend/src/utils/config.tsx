// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfigProvider,
  pollIntervalSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  useConfig,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React from 'react';
import { z } from 'zod';

type ScanServicesConfig = {
  scan: z.infer<typeof serviceSchema>;
};

type ScanConfig = {
  services: ScanServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  pollInterval?: z.infer<typeof pollIntervalSchema>;
};

const configScheme = z.object({
  services: z.object({
    scan: serviceSchema,
  }),
  spliceInstanceNames: spliceInstanceNamesSchema,
  pollInterval: pollIntervalSchema,
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

export const useConfigPollInterval: () => number = () => {
  const config = useScanConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};
