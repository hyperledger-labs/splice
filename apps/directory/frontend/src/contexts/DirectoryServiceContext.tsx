import React, { useContext } from 'react';

import { DirectoryServiceClient } from '../com/daml/network/directory/v0/Directory_serviceServiceClientPb';

const DirectoryContext = React.createContext<DirectoryServiceClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export const DirectoryClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const walletClient = new DirectoryServiceClient(url, null, null);
  return <DirectoryContext.Provider value={walletClient}>{children}</DirectoryContext.Provider>;
};

export const useDirectoryClient: () => DirectoryServiceClient = () => {
  const client = useContext<DirectoryServiceClient | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};
