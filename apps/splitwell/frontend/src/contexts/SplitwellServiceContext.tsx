import { SplitwellServicePromiseClient } from 'common-protobuf/com/daml/network/splitwell/v0/splitwell_service_grpc_web_pb';
import React, { useContext } from 'react';

const SplitwellContext = React.createContext<SplitwellServicePromiseClient | undefined>(undefined);

export interface SplitwellProps {
  url: string;
}

export const SplitwellClientProvider: React.FC<React.PropsWithChildren<SplitwellProps>> = ({
  url,
  children,
}) => {
  const splitwellClient = new SplitwellServicePromiseClient(url, null, null);
  return <SplitwellContext.Provider value={splitwellClient}>{children}</SplitwellContext.Provider>;
};

export const useSplitwellClient: () => SplitwellServicePromiseClient = () => {
  const client = useContext<SplitwellServicePromiseClient | undefined>(SplitwellContext);
  if (!client) {
    throw new Error('Splitwell client not initialized');
  }
  return client;
};
