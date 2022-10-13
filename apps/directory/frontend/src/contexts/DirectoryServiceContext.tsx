import React, { useContext } from 'react';

import { DirectoryServicePromiseClient } from '../com/daml/network/directory/v0/directory_service_grpc_web_pb';

const DirectoryContext = React.createContext<DirectoryServicePromiseClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export const DirectoryClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const walletClient = new DirectoryServicePromiseClient(url, null, null);
  return <DirectoryContext.Provider value={walletClient}>{children}</DirectoryContext.Provider>;
};

export const useDirectoryClient: () => DirectoryServicePromiseClient = () => {
  const client = useContext<DirectoryServicePromiseClient | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};
