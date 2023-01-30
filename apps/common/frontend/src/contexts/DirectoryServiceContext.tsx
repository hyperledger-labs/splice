import * as openapi from 'directory-openapi';
import { ServerConfiguration } from 'directory-openapi';
import { PromiseDirectoryApi } from 'directory-openapi/dist/types/PromiseAPI';
import React, { useContext } from 'react';

const DirectoryContext = React.createContext<PromiseDirectoryApi | undefined>(undefined);

export interface DirectoryProps {
  url: string;
}

export const DirectoryClientProvider: React.FC<React.PropsWithChildren<DirectoryProps>> = ({
  url,
  children,
}) => {
  const configuration = openapi.createConfiguration({
    baseServer: new ServerConfiguration(url, {}),
  });
  const directoryClient = new openapi.DirectoryApi(configuration);

  return <DirectoryContext.Provider value={directoryClient}>{children}</DirectoryContext.Provider>;
};

export const useDirectoryClient: () => PromiseDirectoryApi = () => {
  const client = useContext<PromiseDirectoryApi | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};
