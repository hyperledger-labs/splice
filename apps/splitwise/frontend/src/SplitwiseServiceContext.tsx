import React, { useContext } from 'react';

import { SplitwiseServicePromiseClient } from './com/daml/network/splitwise/v0/splitwise_service_grpc_web_pb';

const SplitwiseContext = React.createContext<SplitwiseServicePromiseClient | undefined>(undefined);

export interface SplitwiseProps {
  url: string;
}

export const SplitwiseClientProvider: React.FC<React.PropsWithChildren<SplitwiseProps>> = ({
  url,
  children,
}) => {
  const splitwiseClient = new SplitwiseServicePromiseClient(url, null, null);
  return <SplitwiseContext.Provider value={splitwiseClient}>{children}</SplitwiseContext.Provider>;
};

export const useSplitwiseClient: () => SplitwiseServicePromiseClient = () => {
  const client = useContext<SplitwiseServicePromiseClient | undefined>(SplitwiseContext);
  if (!client) {
    throw new Error('Splitwise client not initialized');
  }
  return client;
};
