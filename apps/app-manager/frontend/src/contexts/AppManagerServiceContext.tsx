import { BaseApiMiddleware, OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  Middleware,
  createConfiguration,
  ServerConfiguration,
  RequestContext,
  ResponseContext,
  AppManagerApi,
} from 'validator-openapi';

const AppManagerContext = React.createContext<AppManagerApi | undefined>(undefined);

export interface AppManagerProps {
  url: string;
}
class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const AppManagerClientProvider: React.FC<React.PropsWithChildren<AppManagerProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('app-manager'),
      ],
    });

    return new AppManagerApi(configuration);
  }, [url, userAccessToken]);

  return <AppManagerContext.Provider value={friendlyClient}>{children}</AppManagerContext.Provider>;
};

export const useAppManagerClient: () => AppManagerApi = () => {
  const client = useContext<AppManagerApi | undefined>(AppManagerContext);
  if (!client) {
    throw new Error('App manager client not initialized');
  }
  return client;
};
