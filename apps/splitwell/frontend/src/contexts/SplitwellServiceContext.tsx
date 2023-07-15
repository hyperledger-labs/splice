import * as openapi from 'splitwell-openapi';
import { OpenAPILoggingMiddleware } from 'common-frontend';
import React, { useContext, useMemo } from 'react';

const DirectoryContext = React.createContext<openapi.SplitwellApi | undefined>(undefined);

export interface SplitwellProps {
  url: string;
}

export const SplitwellClientProvider: React.FC<React.PropsWithChildren<SplitwellProps>> = ({
  url,
  children,
}) => {
  const client: openapi.SplitwellApi = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('directory')],
    });
    return new openapi.SplitwellApi(configuration);
  }, [url]);

  return <DirectoryContext.Provider value={client}>{children}</DirectoryContext.Provider>;
};

export const useSplitwellClient: () => openapi.SplitwellApi = () => {
  const client = useContext<openapi.SplitwellApi | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('SplitwellClient not initialized');
  }
  return client;
};
