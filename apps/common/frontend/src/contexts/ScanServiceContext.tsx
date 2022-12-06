import { ScanServicePromiseClient } from 'common-protobuf/com/daml/network/scan/v0/scan_service_grpc_web_pb';
import React, { useContext } from 'react';

const ScanContext = React.createContext<ScanServicePromiseClient | undefined>(undefined);

export interface ScanProps {
  url: string;
}

export const ScanClientProvider: React.FC<React.PropsWithChildren<ScanProps>> = ({
  url,
  children,
}) => {
  const ScanClient = new ScanServicePromiseClient(url, null, null);
  return <ScanContext.Provider value={ScanClient}>{children}</ScanContext.Provider>;
};

export const useScanClient: () => ScanServicePromiseClient = () => {
  const client = useContext<ScanServicePromiseClient | undefined>(ScanContext);
  if (!client) {
    throw new Error('Scan client not initialized');
  }
  return client;
};
