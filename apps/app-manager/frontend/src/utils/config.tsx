import {
  authSchema,
  testAuthSchema,
  serviceSchema,
  walletSchema,
  ConfigProvider,
  useConfig,
} from 'common-frontend';
import React from 'react';
import { z } from 'zod';

type AppManagerServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  scan: z.infer<typeof serviceSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type AppManagerConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: AppManagerServicesConfig;
};

const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  services: z.object({
    validator: serviceSchema,
    scan: serviceSchema,
    wallet: walletSchema,
  }),
});

export const ConfigContext = React.createContext<AppManagerConfig | undefined>(undefined);

export const AppManagerConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useAppManagerConfig: () => AppManagerConfig = () =>
  useConfig<AppManagerConfig>(ConfigContext);
