import { DirectoryServicePromiseClient } from 'common-protobuf/com/daml/network/directory/v0/directory_service_grpc_web_pb';
import React, { useContext } from 'react';

const DirectoryContext = React.createContext<DirectoryServicePromiseClient | undefined>(undefined);

export interface DirectoryProps {
  url: string;
}

export const DirectoryClientProvider: React.FC<React.PropsWithChildren<DirectoryProps>> = ({
  url,
  children,
}) => {
  const directoryClient = new DirectoryServicePromiseClient(url, null, null);
  return <DirectoryContext.Provider value={directoryClient}>{children}</DirectoryContext.Provider>;
};

export const useDirectoryClient: () => DirectoryServicePromiseClient = () => {
  const client = useContext<DirectoryServicePromiseClient | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};
