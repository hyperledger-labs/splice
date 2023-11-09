import { BaseApiMiddleware, OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import {
  createConfiguration,
  DirectoryApi,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
} from 'directory-external-openapi';
import React, { useContext, useMemo } from 'react';

const ExternalDirectoryContext = React.createContext<DirectoryApi | undefined>(undefined);

export interface ExternalDirectoryProps {
  url: string;
}

class ExternalApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const ExternalDirectoryClientProvider: React.FC<
  React.PropsWithChildren<ExternalDirectoryProps>
> = ({ url, children }) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: DirectoryApi | undefined = useMemo(() => {
    const externalConfiguration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ExternalApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('directory'),
      ],
    });

    return new DirectoryApi(externalConfiguration);
  }, [url, userAccessToken]);

  return (
    <ExternalDirectoryContext.Provider value={friendlyClient}>
      {children}
    </ExternalDirectoryContext.Provider>
  );
};

export const useExternalDirectoryClient: () => DirectoryApi = () => {
  const client = useContext<DirectoryApi | undefined>(ExternalDirectoryContext);
  if (!client) {
    throw new Error('External Directory client not initialized');
  }
  return client;
};
