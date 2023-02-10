import * as openapi from 'scan-openapi';
import React, { useContext } from 'react';
import { PromiseScanApi } from 'scan-openapi/dist/types/PromiseAPI';

const ScanContext = React.createContext<PromiseScanApi | undefined>(undefined);

export interface ScanProps {
  url: string;
}

export const ScanClientProvider: React.FC<React.PropsWithChildren<ScanProps>> = ({
  url,
  children,
}) => {
  const configuration = openapi.createConfiguration({
    baseServer: new openapi.ServerConfiguration(url, {}),
  });
  const scanClient = new openapi.ScanApi(configuration);

  return <ScanContext.Provider value={scanClient}>{children}</ScanContext.Provider>;
};

export const useScanClient: () => PromiseScanApi = () => {
  const client = useContext<PromiseScanApi | undefined>(ScanContext);
  if (!client) {
    throw new Error('Scan client not initialized');
  }
  return client;
};
